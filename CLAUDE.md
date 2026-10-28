## Reading Code & Architecture

# ULTRA-STRICT TOKEN ECONOMY PROTOCOL
1. <assessment> You (the main agent) MUST execute all coding, refactoring, and file modifications directly. Do NOT delegate coding tasks.
2. <planning> Keep planning extremely brief (1-2 sentences maximum). Output inside <plan> tags.
3. <delegation> Spawn sub-agents ONLY for read-only mechanical tasks: searching code (grep), parsing logs, or reading docs.
- USE ONLY: "claude-3-5-haiku-latest". NEVER delegate to "sonnet" or "opus".
- Sub-agents must return ONLY raw data/lines with zero conversational filler.
4. <review> Integrate fetched data immediately and proceed with direct execution.

## Subprojects

This repo is a monorepo of independent apps that together sync data between a phone,
a TicWatch Pro 5 Enduro (Wear OS), and a web dashboard, and let the phone deploy/remove
watch-app builds onto the watch.

- `backend/` — Spring Boot (Kotlin) REST API. Own Gradle module, included from the root
  `settings.gradle.kts`. Package root: `org.sand.ticwatch.backend`.
- `phone-app/` — Native Android (Kotlin) companion app. Independent Gradle project with
  its own wrapper (not included from root — applying the Android Gradle plugin at the
  root would require an Android SDK just to build `backend`). Package root:
  `org.sand.ticwatch.phone`. Talks to the watch over the Wear OS Data Layer
  (`play-services-wearable`), reads/writes Google Health via Health Connect, and syncs
  to `backend` over REST (Retrofit).
- `watch-app/` — Wear OS (Kotlin) companion app for the TicWatch Pro 5 Enduro. Same
  independent-project setup as `phone-app`. Package root: `org.sand.ticwatch.watch`.
  Collects sensor data via Health Services and pushes it to the phone; receives
  install/uninstall requests and APK bytes from the phone via `PackageInstaller`.
  Note: on a non-system app, both install and uninstall still show the standard
  Android confirmation dialog on the watch — there's no way to make deploys fully
  silent without the app being system/privileged or under MDM.
- `webapp/` — React + TypeScript SPA (Vite), talks to `backend` over REST.

`phone-app` and `watch-app` share a Data Layer message/channel path contract
(`datalayer/DataLayerPaths.kt` in each) that must be kept in sync manually — there is
no shared Gradle module between them.

## Architecture decisions

### phone-app MCP agent

`phone-app/mcp/` hosts a local MCP server (Streamable HTTP, `POST /mcp`, Bearer token),
ported from the MyLibrus app. `McpAgentService` (foreground `specialUse`, wake+Wi-Fi
locks, ongoing notification with "Turn off", auto-off after 30 min idle) runs it only
while the drawer's "MCP agent" switch is on; `AutoStartReceiver` restores it after
reboot/update. Tools are `McpTool` implementations registered in
`AppContainer.mcpTools`; the history tools live in `mcp/history/` (`list_metrics`,
`get_series`, `get_summary`, `get_raw_samples`, `get_delivery_status`) and read through
`SensorHistoryQueries`. `get_series` auto-picks the finest rollup tier that is still
retained and fits `max_points`, so the agent never receives unbounded raw data. Full-history access for
external analysis/forecasting is paged, never unbounded: `get_series`/`get_multi_series` (aligned
multi-type table) take an optional `resolution` and return `next_from` when cut at `max_points`;
`get_raw_samples`/`export_samples` (CSV, all types) return an opaque `next_cursor` over
`(recorded_at, id)`; `get_daily_profile` gives hour-of-day/day-of-week baselines;
`get_sleep_sessions` reads the long-lived `sleep_session` table (see below). `list_metrics` also
reports units/aggregation kind (`SensorMetricCatalog`), the time zone and how far back each tier reaches. Reach it from a PC via
`adb forward tcp:8765 tcp:8765` -> `http://localhost:8765/mcp` (if the local port is
taken, forward another one, e.g. `tcp:18765 tcp:8765`). Bearer token is in the app's
`shared_prefs/mcp_agent_settings.xml` (`run-as`); the service isn't exported, so adb can't
start it - only the drawer switch can.

### phone-app transfer history (acknowledgement pattern)

`phone-app/history/`: every sample written to Health Connect (watch samples + derived
resting HR) is first stored in `sensor_history.db` (app `databases/`, not cache) as
`PENDING`, then `AcknowledgingHealthRecordWriter` (decorator over `HealthRecordWriter`)
writes per sensor type and marks rows `DELIVERED`/`FAILED`. Retention (3/7/15/30 days,
default 7) lives in Settings; `SensorHistoryPurgeWorker` (WorkManager, unique daily job)
deletes rows by `received_at` and VACUUMs, then `FailedDeliveryRetrier` re-sends `FAILED`
rows through the same `AcknowledgedDelivery` (safe: a failed type's `insertRecords` batch is
atomic, so nothing of it reached Health Connect). Settings also shows the DB size (main +
WAL/SHM files) and has a "Clear history" button.

Schema v2 (compact): enum columns are INTEGER codes (`SensorTypeCodes` - explicit, never
`ordinal`; `DeliveryStatus.code`), rowid PK without AUTOINCREMENT, index `(type,
recorded_at)` for range queries, partial index on FAILED rows for the retrier. Every
sample is also folded **on insert** (same transaction, `SensorRollupWriter`) into
`sensor_rollup` (`WITHOUT ROWID`, min/max/sum/count per type x `RollupResolution` x
bucket): 5 min kept 90 days, 1 hour 365 days, 1 day (local midnight) forever, purged by
`SensorHistoryPurger` independently of the raw retention. Incremental rather than
recomputed from raw rows, so late watch batches land in their bucket and the raw purge
never shrinks a rollup. minSdk 26 SQLite has no UPSERT: update, insert on miss. Schema v3 adds
`sleep_session` (start PK, end; `INSERT OR REPLACE` so a re-sent session doesn't duplicate), seeded on
upgrade from the retained SLEEP rows and purged by its own Settings retention in months
(`SleepSessionRetention`: 3/6/12/24/forever, default 12). Both it and the rollups are
`HistoryProjection`s fed in the raw insert's transaction - add new long-lived derived tables the same way.
Schema v5 adds `sleep_stage` (start PK, end, `SleepStageType` code, `session_start` = the enclosing
`sleep_session` key; `SleepStageWriter`, linked on insert or later by `SleepSessionWriter`), purged with the
sleep-session retention. The SLEEP mapper puts the stored stages (`SleepStageFactory`, clipped/non-overlapping)
on the `SleepSessionRecord`.

Sleep stages: Health Services has no ASLEEP state on this watch, so `VendorSleepStageSource` (watch) listens to
`mobvoi_sleep` (33171123, preferred) and TicMotion sleep (33171126), decodes values[0] as 7 awake/8 rem/9 light/
10 deep (`MobvoiSleepStageDecoder` - inferred from the first full night, not vendor-documented; 6 = in bed, not
yet asleep, 99 = night over) and `SleepStageSegmenter` emits SLEEP_STAGE intervals plus the SLEEP session when a
non-sleep event or a >2 h silence ends the night (the sensor only reports changes). Segmenter state is in-memory:
a service restart mid-night loses that night's session. A wrongly recorded night is repaired with the
`DEBUG_REPLACE_SLEEP_NIGHT` broadcast (`--es payloadPath <sensor-push JSON in the app's files dir> --el
fromEpochMs --el toEpochMs`): `SleepNightReplaceWorker` deletes this app's HC sleep sessions and stored sleep
in the range, then ingests the payload through the normal push path.

Phone-derived: `StepsCadenceDeriver` (STEPS intervals of 30 s-10 min at >= 60 steps/min -> STEPS_CADENCE,
`StepsCadenceRecord`); `BasalMetabolicRateDeriver` (Mifflin-St Jeor from the profile's birth year/sex/height/weight,
first push of each local day or on a profile change, `BasalMetabolicRateRecord` with a per-day client record id).

Body temperature: `CorrelatedBodyTemperatureEstimator` maps SKIN_TEMPERATURE to a BODY_TEMPERATURE
estimate for readings the movement qualifiers accept (exercise + 30 min recovery, step cadence -
activity cools the wrist while the core warms), trying `CoreTemperatureModel`s in order:
`DualHeatFluxCoreModel` (`T_skin + k*(T_skin - T_ambient)` + resting-HR rise as fever, ~0.1 °C/bpm
+ `CircadianOffset`; needs an AMBIENT_TEMPERATURE window, i.e. the watch's NST112 case sensor,
history-only since Health Connect has no record for it), else the skin-only `SkinOffsetCoreModel`
behind the HR-elevation qualifier, which during sleep uses the personal nightly skin baseline
(`NightlySkinTemperature` as `SkinBaselineSource`). k=0.27 is uncalibrated against a thermometer. `BodyTemperatureBackfill`
(one-time `BodyTemperatureBackfillWorker`, enqueued at app start, guarded by `BACKFILL_VERSION`;
force with the `DEBUG_BACKFILL_BODY_TEMPERATURE` broadcast) runs the same estimator over retained
SKIN rows that have no BODY row yet, with history rows as context. The MCP tool
`get_nightly_skin_temperature` gives per-sleep-session mean skin temperature vs. the median of the
previous 14 nights (from rollups). The watch logs every `mobvoi_temperature` channel's window mean
to `temperature_channels.log` (pulled with `DEBUG_PULL_WATCH_SLEEP_LOG`) to check whether channels
0/3 are a vendor-calibrated core estimate. Run purge+retry on demand with
`adb shell am broadcast -n org.sand.ticwatch.phone/.datalayer.DebugBridgeReceiver -a
org.sand.ticwatch.phone.action.DEBUG_RUN_HISTORY_MAINTENANCE` (`cmd jobscheduler run -f`
doesn't work: WorkManager skips a periodic job whose period isn't due).

### Workout routes (ExerciseRoute)

Schema v4 adds `exercise_route_point` (`WITHOUT ROWID`, PK `(recorded_at, source)`, `RouteSource`
PHONE/WATCH; `SqliteExerciseRouteStore`). Points carry no session id: the watch only emits an EXERCISE
sample once the workout has ended, so `ExerciseRouteFactory` joins fixes to the session by time
(`[start, end]`, the source with more fixes wins) and the EXERCISE mapper puts the `ExerciseRoute` on the
`ExerciseSessionRecord` (needs `WRITE_EXERCISE_ROUTE`). Phone track: the watch's `WorkoutStateNotifier`
sends `/workout/state` (`{"active": bool}`) on Health Services EXERCISE transitions; `PhoneMessageRouter`
-> `ServiceWorkoutRouteRecorder` starts/stops `WorkoutLocationService` (FGS `location`, Fused 4 s fixes,
self-stops after 6 h). Started from the background, so it needs location "Allow all the time" granted in
system Settings, otherwise the start is refused and logged. Watch track: `WatchRouteRecorder` (a
`UserActivityObserver` next to `WorkoutStateNotifier`) runs `GpsLocationFixSource` (platform
`LocationManager` GPS, 4 s) during EXERCISE and sends fixes on `/workout/route` (JSON array,
`WorkoutPayloads` in `watch/route/`) in batches of 60 plus the rest at workout end; needs
ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION on the watch (MainActivity asks, background ones
sequentially). They must arrive before the EXERCISE sample (next sync flush: every 30 min or at 150 buffered samples) to land on
its record. Route points follow the raw retention; MCP tool `get_exercise_route` pages them.

### watch-app automatic walk workouts

`watch/autoworkout/`: `AutoWorkoutController` (run from `SensorCollectionService`) watches the platform step
counter (`StepCounterSensorSource`, wake-up variant, 30 s batches - no wakeups while still) through
`BriskWalkDetector` (>= 90 steps/min over a 1 min window, 2 min pauses tolerated). After 5 min of brisk walking it
starts a Health Services WALKING exercise (`HealthServicesWorkoutSession`, `ExerciseClient`, GPS left to
`WatchRouteRecorder`) - immediately in AUTO mode, after the notification's "Start" in ASK mode (default; the
dashboard card cycles Off/Ask/Automatic, `SharedPreferencesAutoWorkoutSettings`) - and ends it after 3 min below
60 steps/min or on the ongoing notification's "End" (service intents `ACTION_START/END_AUTO_WORKOUT`). It never
starts while another app's workout runs and acts on each brisk stretch once. The exercise flips the passive state
to EXERCISE, so the existing session/route/phone pipeline records it; `AutoWorkoutStartLedger` backdates that
session's start to where the walk began. Re-checks run on an `AlarmWindowScheduler` every minute only while a
stretch or workout is open.

### watch-app adaptive polling and Baseline Snapshot

`watch/sensors/adaptive/`: every collected sample (`SensorCollectionService.onCollected`) goes through
`AdaptiveSensorScheduler`'s `AdaptiveTrigger`s (Strategy, one alert state per `TriggerId`): `RestingHeartRateTrigger`
(HR > 100 / < 50 while no steps for 2 min and no workout -> HEART_RATE continuous, HRV + SpO2 every 5 min),
`DesaturationTrigger` (SpO2 < 94 -> SpO2 again 60 s after the window), `SkinTemperatureRiseTrigger` (>= 1 °C over the
median of the in-memory 6 h trend, awake only -> every 5 min). `MonitoringProfile` per `MeasurementGroup`: IDLE (30 min)
-> ALERT on an abnormal reading -> RECOVERY on the first normal one (boost interval x2) -> IDLE after 10 min of normal
readings; an ALERT without readings for 15 min starts recovering. Decay is lazy (on samples and reads) - no timer of its
own. `VendorWindowSampleSource` paces each `AggregatorGroup` (now keyed by `group`) by `WindowCadence.intervalFor`,
counted from its last window's end; groups due within 5 min join a starting window; `cadence.changes` interrupts the
alarm wait. `SensorWindowMeasurer` (extracted) does the actual window. `BoostedHeartRateSampleSource` runs the Measure
API (thinned to 1 per 10 s) only while HEART_RATE isn't IDLE. Add a trigger by registering it in the service.

Baseline Snapshot (`watch/snapshot/`): `BaselineSnapshotCapturer.captureFullSnapshot()` - worn check, 10 s
accelerometer + gyroscope stillness probe, then all window groups in one 3 min window + live HR median. Started by the
watch's "Snapshot" page or the phone's `/command/capture_snapshot` (`{"request_id"}`; the router forwards it to the
service as `ACTION_CAPTURE_SNAPSHOT`). `SnapshotController` sends `/snapshot_result` (status, samples, HR in the BP
request keys); if the phone is unreachable the samples go into the regular sync unflagged. Phone (`phone/snapshot/`):
`StoringSnapshotResultReceiver` ingests them with `SensorSample.snapshot = true` (history schema v7
`sensor_history.is_snapshot`, `get_raw_samples` shows `snapshot: true`), estimates blood pressure from the snapshot HR
(not flagged), records the result in `ProcessSnapshotResults` and answers `WatchReplies`. Phone triggers: the Metrics
tab's "Watch snapshot" card and MCP `trigger_health_snapshot` (`wait`, default true, up to 5 min).

### watch-app on other Wear OS watches (ExtendedMetricsProvider)

Sleep stages, SpO2, HRV, breathing rate and skin temperature come from an `ExtendedMetricsProvider` (`watch/sensors/`)
picked once by `MetricsProviderFactory` in `SensorCollectionService`: `MobvoiMetricsProvider` (vendor sleep source,
night breathing monitor, body window groups, `mobvoi_hr_alert`, vendor recorders) when `mobvoi_sleep` + `psp_spo2`
exist, else `GenericWearOsMetricsProvider` (public APIs only: Health Services has no sleep-stage/SpO2/skin-temperature
DataType, so sleep = the shared passive ASLEEP `StateSessionTracker`, mirrored into the sleep state for the phone snore
relay; no body windows/night monitor, so no wasted PPG or wake locks). `Build.MANUFACTURER`/`BRAND` is only logged -
sensor presence decides. Passive HS data, environment window group, sync and wire format are provider-independent.
Add a vendor (e.g. Samsung SDK) as another provider + factory rule. Mobvoi `uses-permission`s are inert elsewhere.

### watch-app measurement windows and the off-body gate

`VendorWindowSampleSource` (3 min every 30 min, paced by `AlarmWindowScheduler` - an
`ELAPSED_REALTIME_WAKEUP` alarm, never a coroutine `delay`, whose monotonic clock stops while the
watch's CPU is suspended) measures `AggregatorGroup`s, each behind a
`WindowGate`: the body groups (SpO2; RMSSD + breathing rate; skin temperature) are gated by
`OffBodyDetector` (`TYPE_LOW_LATENCY_OFFBODY_DETECT`, checked at window start and end; unknown state
= worn) and, except skin temperature, closed while asleep (the night monitor below takes over); the environment group (NST112 ambient temperature, `mspc110` pressure, `opt3007` light, all
`TimeWeightedMeanAggregator`s) always runs. RESPIRATION_RATE goes to Health Connect
(`RespiratoryRateRecord`); BAROMETRIC_PRESSURE and AMBIENT_LIGHT are history-only like ambient
temperature. SpO2 has its own body group with a `StillnessQualifier` (`WindowQualifier`: listens to
the accelerometer for the whole window, drops the group's results if >15% of events deviate >1.5 m/s²
from gravity - PPG reads motion as desaturation) and reports the median of the converged readings,
not the latest one. Breathing rate comes from the `psp_rri` RR series (`RriRespirationRateAggregator` ->
`RespiratorySinusArrhythmiaEstimator`: 4 Hz resample, detrend, Hann, strongest 0.1-0.5 Hz peak if >= 3x the
band mean); `psp_rr` read all zeros, so `RawEventRecorder` only logs it raw.

Heart-rate sensors (probe 2026-10-27): passive Health Services HR is the background source, the Measure API runs
only while `MainActivity` is visible, `ExerciseClient` during auto workouts. Platform `TYPE_HEART_RATE` (21) is
`psp_hr`, the same vendor pipeline - unused. Undecoded, recorded raw for decoding: `psp_lphr` (33171109) by the night
monitor (`RawEventRecorder` -> `heart_rate_sensor.log`, PPG is lit anyway), and `mobvoi_hr_alert` (33171609,
wake-up "Inactive HR Sensor") by `HeartRateAlertSampleSource`, registered always (`heart_rate_alert.log`) and sent
as HEART_RATE_ALERT (value = raw values[0]; history only, Health Connect has no alert record). Both logs come
with `DEBUG_PULL_WATCH_SLEEP_LOG`.

Night breathing monitor: `VendorSleepStageSource` feeds `TrackedSleepState` (open session, <= 2 h since the
last sleep event); while asleep and worn `NightBreathingSampleSource` keeps `psp_spo2`/`psp_rri`/accelerometer
registered all night (re-registering restarts SpO2's ~2 min convergence) under a non-refcounted partial wake
lock (the battery cost), and every 5 min emits SpO2/RMSSD/breathing rate as epoch intervals, completed
`DesaturationDetector` events (OXYGEN_DESATURATION: >= 3 points below the 2-min median for >= 10 s) and
SNORING (fraction of the epoch, from `MicrophoneSnoreMeter` listening to the first minute: 8 kHz frame
features only, nothing stored; `SnoreDetector` wants loud low-pitched 0.3-3.5 s bursts repeating every 2-9 s).
Microphone needs RECORD_AUDIO and the FGS `microphone` type, which `SensorCollectionService` adds in
`onStartCommand` - only granted when started from `MainActivity`; after a boot start snoring is skipped.

### phone-app derived Health Connect metrics

Derivers (registered in order in `AppContainer`; those needing other pushes read history + push through
`SampleWindowReader`): `ActivityIntensityDeriver` (minute HR as % of HR reserve, >= 2 min runs ->
`ActivityIntensityRecord`, behind `FeatureGatedMapper` since only newer Health Connect has it - hence
`connect-client:1.2.0-alpha02`; alpha04+ need compileSdk 37 / AGP 9.1), `AutoExerciseDeriver` (cadence >= 90
for >= 10 min with no workout overlapping -> walking/running `ExerciseSessionRecord`, client id by start),
`RunningPowerDeriver` / `RunVo2MaxDeriver` (outdoor RUNNING sessions' GPS track via `ExerciseTrack`; VO2 max by
ACSM running equation over %HRR, `MEASUREMENT_METHOD_OTHER`), `ProfileMeasurementDeriver` (WEIGHT/HEIGHT on
profile change), and per finished night `NightlySkinTemperatureDeriver` (one `SkinTemperatureRecord`: 14-night
baseline + each window as delta; per-window SKIN_TEMPERATURE is history-only now), `BasalBodyTemperatureDeriver`
and `SleepBreathingSummaryDeriver` (ODI per SpO2-monitored hour, snoring minutes - history only, MCP
`get_sleep_breathing`). Exercise sessions carry per-km laps. `SleepStageFactory` falls back to
`HeartRateSleepStageEstimator` when stored stages cover < 50% of a session.

### phone-app health scores (sleep score, HRV status, training load, stress, light)

`insights/`: pure, unit-tested calculators fed by small history readers. Stored as history-only samples
(no schema change; new `SensorTypeCodes` 36-42, so the day rollups keep them forever): `SleepScoreDeriver`
(after the breathing summary) -> SLEEP_SCORE per night via `HistorySleepScoreSource` + `SleepScoreCalculator`
(weighted duration/deep+REM/efficiency/nocturnal HR dip vs the 12 h before/ODI, renormalised over components
with data); `CardioLoadDeriver` (health/, after `AutoExerciseDeriver`, `InstantLog`-guarded) -> TRAINING_LOAD
(Banister TRIMP) and HEART_RATE_ZONE_1..5 minutes (Karvonen, Tanaka max HR, needs birth year) per workout.
Computed on read from rollups: `HrvStatusClassifier` (7-day nightly RMSSD mean vs 28-day mean ± 0.75 SD;
Low < mean - 1.5 SD, Poor < 20 ms), `TrainingLoadBalanceCalculator` (acute 7-day sum / chronic 28-day weekly
mean), `DailyStressCalculator` (5-min RMSSD buckets per `StressLevel`, sleep and walking buckets excluded),
`CircadianLightExposureCalculator` (>= 1000 lux 06-12 local, each window reading covers <= 30 min).
`HistoryHealthScoresSource` composes them for the Metrics tab and MCP (`get_sleep_score`, `get_hrv_status`,
`get_training_load`). After every stored sensor push `WatchHealthScoresPublisher` sends them (only when
changed) on `/insights/scores` (`HealthScoresPayload`, mirrored on the watch); the watch keeps the last one in
`SharedPreferencesHealthScoresStore` and shows "Sleep score" and "Training load / HRV / stress / daylight" pages.

### phone-app space weather (geomagnetic Kp / solar flux)

`spaceweather/`: `SpaceWeatherRefreshWorker` (WorkManager, every 3 h, network required; force with the
`DEBUG_REFRESH_SPACE_WEATHER` broadcast) fetches NOAA SWPC's free products - `noaa-planetary-k-index-forecast.json`
(3-hour Kp: ~7 days observed/estimated + 3 days predicted) and `27-day-outlook.txt` (daily largest Kp, Ap, F10.7
flux) - and `SpaceWeatherAssembler` joins them into today + 5 local days (outlook Kp only for days the 3-hour slots
don't fully cover). `GeomagneticLevel` = NOAA G-scale on Kp rounded (4.67 = G1); `SolarActivityLevel` by F10.7.
Only the latest fetch is kept (`SharedPreferencesSpaceWeatherStore`, `SpaceWeatherPayload` JSON), no history table.
Shown as the Metrics tab's last card, MCP `get_space_weather` (incl. all 3-hour slots), and sent on
`/insights/space-weather` to the watch's "Geomagnetic" page (`PhonePayloadStores.spaceWeather`). The router re-sends
every `WatchStatePublisher` in `AppContainer.watchStatePublishers` (deduplicated) after each stored sensor push.

### phone-app blood pressure (calibrated cuffless estimate)

Dashboard tab "Blood Pressure" (`BloodPressureScreen`) over `health/BloodPressureMonitor`. Calibration = a cuff
reading the user types in plus the wrist signals at that moment (live HR median, else the day's resting HR; RMSSD,
skin temperature), kept as `BloodPressureCalibration` by `SharedPreferencesUserProfileStore`
(`BloodPressureCalibrationStore`), stale after 30 days. `BloodPressureEstimator`: `SYS = base + 0.5*dHR +
8*(1 - RMSSD/RMSSD_base)` (±25), `DIA = base + 0.3*dHR - 1.5*dT_skin` (±15) - uncalibrated literature-order
coefficients, trends only. Refused when the last 5 min show > 40 steps/min (`WristSignalsReader`; watch pushes arrive
in batches, so recent walking may not be stored yet). Saved only on the user's "Save" as BLOOD_PRESSURE_DIASTOLIC +
BLOOD_PRESSURE_SYSTOLIC (codes 44/45, same timestamp) through the acknowledging writer; the systolic mapper looks the
diastolic half up in the history and writes one `BloodPressureRecord` (client id `blood_pressure_<epochMs>`, so
deleting a reading also deletes it from Health Connect; raw rows are erased, rollups keep it). MCP `get_blood_pressure`.

Watch page "Blood pressure" (`BloodPressurePage`): latest reading in large digits, the day chart (cuff and wrist estimates
mixed) and a "Measure" button. Measure = the wrist estimate, never the cuff:
`PhoneBloodPressureMeasureRequester` flushes the watch's sample buffer, then sends `/blood-pressure/measure` with the
on-screen (Measure API) heart rate, which `WristSignalsReader` prefers over stored HR when <= 2 min old;
`WatchBloodPressureRequestHandler` runs `BloodPressureMonitor.measureAndSave` (auto-saved, unlike the phone tab's Save
step) and answers a status line on `/blood-pressure/measure/result`. `BloodPressureLog` notifies a
`BloodPressureLogListener` on every save/delete -> `WatchBloodPressurePublisher` sends the readings on
`/blood-pressure/history` (the last 24 h, max 400, newest first; also after each sensor push, deduplicated).
Automatic: `AutoBloodPressureController` (watch, `SensorCollectionService`, alarm-paced every 5 min, toggle "Auto at rest" on the page or the phone's Settings > Watch features, default off - see
synced watch settings below) sends the same request when the
`WindowGate`s are open (worn, `NoWorkoutGate`, `RecentStepsGate` <= 30 steps in 5 min from the step counter) and a
20 s gyroscope probe (`GyroscopeStillnessQualifier` via `SensorQualifierProbe`) accepts; every estimate is saved to
history. Both apps chart the day (`dashboard/BloodPressureChart.kt`, mirrored): watch page, phone Metrics card and the
Blood Pressure tab (where the selected point gets Delete) - tap a point for its details; no text list anymore.
Watch side, every phone -> watch payload is a
`PhonePayloadStore` in `PhonePayloadStores` (one per process, keyed by path for `WatchMessageRouter`).

### Synced watch settings (battery-heavy features)

`WatchSettings` (`auto_blood_pressure`, `log_capture`, `changed_at`; both off by default) lives on both sides
(`watch/settings/`, `phone/watchsettings/`, mirrored) and travels on `/settings/watch` in both directions, newest
`changed_at` wins. A local change is stored and sent; the phone re-sends its copy after every sensor push
(`WatchStatePublisher`, deduplicated) and the watch answers an older copy with its own, so a change made while the
link was down converges. Switches: watch Settings page ("Log capture") and blood pressure page ("Auto at rest");
phone Settings > Watch features. Add further battery toggles to the same payload.

Snore analysis: `snore_analysis` (default off) + `snore_microphone` (`SnoreMicrophone` WATCH/PHONE, default WATCH) in
the same payload (optional keys, so an older peer still decodes). WATCH: the night monitor's `MicrophoneSnoreMeter`
behind `SettingsGatedSnoreMeter`. PHONE: `PhoneSnoreMonitorRelay` (decorator over the sleep-state sink fed by
`VendorSleepStageSource`) sends `/sleep/snore-monitor` (`{"active": bool}`, `LatestMessageSender` - also behind
`WorkoutStateNotifier`) when a sleep session opens/closes; the phone's `ServiceSnoreMonitorControl` forwards it to
`BluetoothLinkServerService`, which hosts `phone/snore/PhoneSnoreMonitor` (mirrored `SnoreDetector`, back-to-back
5-min epochs -> SNORING rows with `SampleSource.PHONE`, wake lock, capped at 12 h since a night ended by sensor
silence sends no stop). Microphone needs RECORD_AUDIO and the service's `microphone` FGS type, added in
`onStartCommand` only when MainActivity starts it - after a boot start phone snoring is skipped until the app opens.

### phone-app cardio: geomagnetic alerts, medication log, doctor's report

Geomagnetic alerts: after each refresh `SpaceWeatherRefresher` runs `GeomagneticAlerter` - `GeomagneticAlertPolicy` takes
the peak Kp of the 3-hour slots running or starting within 48 h and alerts from `GeomagneticLevel.ACTIVE` (Kp 4; G1+ is
a storm); `SharedPreferencesGeomagneticAlertLedger` re-announces only a higher level or after 24 h.
`SystemGeomagneticAlertNotifier` posts on `channel_space_weather_alerts`; the tap opens `MainActivity.cardioAdviceIntent`
(Blood Pressure tab + `CardioAdviceBottomSheet`, advice from `CardioAdvisor` per `DrugClass` of the regimen).

`medication/`: `PresetMedicationRegimen` (Nedal 1/4 = nebivolol 1.25 mg; Noliprel Forte 1/2 = perindopril 2.5 +
indapamide 0.625 mg; no editing UI). The Blood Pressure tab's button logs an intake into `cardio.db` - the app's
only **Room** database (`CardioDatabase`, KSP, schemas exported to `phone-app/schemas/`; bump `VERSION` with a `Migration`),
kept apart from the hand-written `sensor_history.db`: user-entered cardio data goes there, sensor data stays in the
history. `RoomMedicationIntakeLog` implements `MedicationIntakeLog` (table `medication_intake`); intakes are drawn as vertical lines on
the BP chart, and `MedicationEffectAnalyzer` compares watch HR (15-min median) / nearest BP reading at +1/2/4 h with the
30 min / 2 h before (onset = first 15-min HR median >= 5 bpm below baseline).

`report/`: `CardioReportGenerator` (1-7 days) -> `CardioReport` (ABPM-style means for 24 h / day 07-23 / night 23-07,
dipping % and pattern, pulse pressure; night resting HR = lowest 15-min mean per night, morning surge vs it, daytime
runs > 90 bpm >= 2 min outside workouts, median medication onset; cuff readings vs nearest wrist estimate within
30 min) -> `CardioReportFormatter` (`ReportDocument`) -> HTML / Markdown `CardioReportRenderer`s. Exported from the tab
via `AndroidCardioReportExporter`: WebView + `PrintManager` (print / save as PDF) or `ACTION_SEND` Markdown. Patient name
comes from Settings > Profile (`PatientNameStore`).

### phone-app cardio advisor (readiness, widget, MCP)

`advisor/`: Dashboard tab "Advisor" (`CardioAdvisorScreen`, MVI via `CardioAdvisorStore`: `AdvisorUiState` +
`AdvisorIntent`), home-screen widget (`advisor/widget/`, RemoteViews `CardioAdvisorWidgetProvider`, 30 min
`updatePeriodMillis` plus `AdvisorWidgetRefresher` in `watchStatePublishers` and the BP log listener; tap opens
`MainActivity.advisorIntent`) and MCP `get_cardio_readiness`, all over one `CardioReadinessSource`.
`ReadinessSnapshotReader` gathers rollup signals (15-min HR else resting HR vs the 14-day mean of the same local hour,
2 h RMSSD, last BP within 24 h + its ABPM dipping %, SLEEP_SCORE, 24 h steps, 48 h TRIMP, last intake, Kp slot).
`CardioReadinessEngine` = 100 + `ReadinessRule`s (diastolic >= 90/95 -25/-40, HR > norm +10/+20 -15/-30, RMSSD < 25
-15, > 15k steps after sleep < 70 -15, Kp >= 5 -10, good sleep + 10-20 % dip +10; info rules for TRIMP/medication) ->
`ReadinessState` bands 80/55/35 with `AdviceSelector` (no-stimulants card when tachycardia dominates).
`ReadinessForecaster` re-scores the next 12 h with the same engine: `MedicationEffectCurve` (ramp 2 h, plateau to 8 h,
50 % at 24 h) lowers diastolic/HR excess by up to 8, 06-09 adds a 5 mmHg morning surge, HR norm and Kp follow the hour;
Optimal hours 07-20 are walk windows. Add rules by registering them in `AppContainer.cardioReadiness`. Heuristic only.

### phone-app Bioland cuff (BLE blood pressure monitor)

`bpm/`: the bonded "Bioland-BPM" cuff (Medek-branded, BLE, LE legacy bond) has no standard 0x1810 service; vendor
frames on service 0x1000 (`BiolandProtocol`, reversed, not documented): commands `5A 0A <cmd> yy MM dd HH mm ss cs` to
0x1001, replies `55 len type ... cs` on 0x1002 notify, cs = (sum + 2) & 0xFF. Commands: 00 = device info (battery %,
ASCII serial) and 03 = upload not-yet-uploaded stored records (one type-03 frame each); both set the cuff clock. 01
answered `EE` once and may start a measurement (unconfirmed); 05 seemed to power it off; 04/06 no reply. Unprompted:
type 02 = live cuff pressure (byte 3 toggles 00/01 while deflating, likely beat marks), 03 = result `yy MM dd HH mm ff
SYS 00 DIA PULSE` (ff 0x0A on a stored record). `BiolandCuffClient` (autoConnect GATT, re-armed on disconnect, hosted
by `BluetoothLinkServerService`) sends 00 + 03 on every connection, so the cuff clock is the time reference after a
battery change too. `CuffReadingRecorder` stamps readings with the cuff clock when within 365 days .. now+10 min (else
phone time for live, drop for stored), skips one within 3 min of a stored cuff reading, saves BP + pulse (HEART_RATE
row, same instant) through `BloodPressureLog`, then `BloodPressureMonitor.calibrateFromCuff`: only with a watch HR
(source WATCH) within 5 min of the reading and newer than the current calibration, retried at +10/20/30 min since watch
batches lag. Battery/serial in `SharedPreferencesCuffStatusStore`, shown on the Blood Pressure tab. Schema v6 adds
`sensor_history.source` (`SampleSource`: WATCH default, BLOOD_PRESSURE_CUFF); mappers put the source's `Device` on the
record (series split per source), and only cuff blood pressure goes to Health Connect - wrist estimates stay
history-only. The cuff connects only while on (sleeps ~2 min after use). `DEBUG_PROBE_BPM` (`--es frames
"5A0A00{NOW};..."` `--es characteristic 1001`) runs `BpmGattProbe`: logs all services/notifications, writes the frames.

### Reaching the watch without adb: the phone-side debug bridge

The TicWatch is frequently unreachable by adb directly (it drops Wi-Fi/wireless
debugging whenever it's off-wrist — see the "watch debug bridge" project notes), so
the primary way to deploy to and inspect the watch is **through the phone**, over the
same Bluetooth link the apps use in production — not via adb Wi-Fi/`Debug over
Bluetooth` tunneling to the watch itself.

- `phone-app/datalayer/DebugBridgeReceiver.kt` — exported `BroadcastReceiver`,
  adb-triggerable only on the **phone** (which is reliably on adb via USB/Wi-Fi):
  - `ACTION_PING_WATCH_SENSORS`
    (`org.sand.ticwatch.phone.action.DEBUG_PING_WATCH_SENSORS`): asks the watch for a
    one-shot sensor snapshot.
  - `ACTION_PROBE_WATCH_CAPABILITIES`
    (`org.sand.ticwatch.phone.action.DEBUG_PROBE_WATCH_CAPABILITIES`): watch replies with
    its Health Services passive/measure/exercise capabilities, full `SensorManager` list
    and Mobvoi content providers, logged on the phone as chunked
    `PhoneMessageRouter: Watch capabilities [n]` lines.
  - Must be triggered with an **explicit component** (`-n
    org.sand.ticwatch.phone/.datalayer.DebugBridgeReceiver`), not just `-a`
    — implicit broadcasts to a background app get silently dropped by Android's
    background-execution limits once the app isn't foregrounded.
  - Goes out over `TransportFactory`'s transport (Bluetooth Classic primary, Wear Data
    Layer fallback — see `transport/` in both apps), reaches the watch's
    `WatchMessageRouter`, and the reply comes back to the phone's own
    `PhoneMessageRouter`/`BluetoothLinkServerService`, landing in the **phone's**
    logcat. The watch itself never needs to be on adb for either the request or the
    response.
- `phone-app/datalayer/WatchAppInstallService.kt` — exported foreground `Service`
  (extra `apkPath`), pushes a watch-app APK to the watch via the same `WatchAppManager`
  path the in-app "Update app on watch" button uses. **Not** triggered through
  `DebugBridgeReceiver`/a broadcast: Android 12+ blocks an app from calling
  `startForegroundService()` on itself from a background-executing `BroadcastReceiver`
  (`BFGS denied`), and a bare `goAsync()` coroutine doesn't survive either — once the
  receiver returns, the process has no active component left and gets suspended by the
  cached-app freezer mid-transfer (no crash, no log, just silently stuck). adb's shell
  UID isn't subject to either restriction, so start the service directly by explicit
  component instead of broadcasting to it.
- Watch app log: `WatchApplication` always records uncaught crashes; `LogcatFileRecorder` (tees this
  process's own logcat, `-v year`) runs only while the synced "Log capture" setting is on (default off - battery) into `RotatingLogStore` (`filesDir/logs/app-yyyyMMdd-HH.log`,
  3 days). Queried over `/debug/logs/request` (JSON filter with `request_id`) -> `LogQueryHandler`;
  the phone's MCP tool `get_watch_logs` sends it and waits on `WatchReplies` (process-wide, since the
  reply lands in whichever listener service the transport woke). Request-aware debug routes take a
  `DebugQuery`; payload-less probes keep using `DeviceCapabilityProbe`.
- Watch screenshots: `adb shell am broadcast -n org.sand.ticwatch.phone/.datalayer.DebugBridgeReceiver -a
  org.sand.ticwatch.phone.action.DEBUG_CAPTURE_WATCH_SCREEN [--ei page <n>]` -> `/debug/screenshot/request` ->
  `ScreenshotQuery` (watch `screenshot/`) asks `VisibleScreen.capturer` (registered by `MainActivity` between
  onStart/onStop, so the app must be open on the watch) = `WindowScreenCapturer`: switches the dashboard pager via
  `DashboardPageNavigator`, waits 1.2 s, `PixelCopy` of the window -> base64 PNG JSON (`page`, `page_count`) on
  `/debug/screenshot/response`. The phone's `FileWatchScreenshotSink` writes `files/watch_screenshots/page-<n>-<ms>.png`
  (pull with `adb exec-out run-as org.sand.ticwatch.phone cat files/watch_screenshots/<name>`). Only this app's own
  window - other apps / the watch face would need MediaProjection consent.
- **One-shot dev loop: `deploy.bat` / `deploy.ps1` at the repo root.** Builds both apps,
  exits if neither APK changed since the last successful deploy (hashes in
  `build/deploy-state.json`), installs phone-app on the non-watch adb device, stages the
  watch APK in the phone app's internal storage via `run-as` (an adb-created
  `Android/data` dir gives the app `EACCES`), starts `WatchAppInstallService` and waits
  for its logcat result. Flags: `-Force`, `-NoWait`, `-WaitTimeoutSec`. The manual steps
  below are what it automates.
- Practical trigger sequence (via the `adb-mcp` MCP tools — `shell` for the
  broadcast/service start, `logcat` for the reply):
  ```
  adb shell am broadcast -n org.sand.ticwatch.phone/.datalayer.DebugBridgeReceiver \
      -a org.sand.ticwatch.phone.action.DEBUG_PING_WATCH_SENSORS
  adb logcat -s PhoneMessageRouter:I BluetoothLinkServer:I DebugBridgeReceiver:I
  ```
  For installs, push the APK to the phone's app-private storage first, then start
  `WatchAppInstallService` directly with `--es apkPath <path>`:
  ```
  adb push watch-app-debug.apk /sdcard/Android/data/org.sand.ticwatch.phone/files/watch-app-debug.apk
  adb shell am start-foreground-service -n org.sand.ticwatch.phone/.datalayer.WatchAppInstallService \
      --es apkPath /storage/emulated/0/Android/data/org.sand.ticwatch.phone/files/watch-app-debug.apk
  ```
  This returns immediately; the transfer itself (60-120+s, sometimes several minutes,
  for a ~26MB APK over classic Bluetooth Classic) runs in the background under the
  foreground-service notification, with no progress logging until it finishes — a long
  silent wait is expected, not a hang. Payloads > 256 KB go over the resumable RFCOMM
  frame (`TransportProtocol`: SHA-256 handshake + resume offset, 64 KB chunks each ACKed,
  hash verified at the end; watch keeps `noBackupFilesDir/incoming_transfers/<sha>.part`),
  so a dropped link resumes instead of resending the APK; a watch-app that predates it
  replies a plain ACK and the phone falls back to the one-shot frame. Every phone send
  goes through the process-wide `LinkArbiter` (`TransportFactory`): a send over the
  resumable threshold is BULK and pauses at the next chunk boundary whenever an
  INTERACTIVE send (ping, probe, command) is waiting - RFCOMM closes the socket and resumes
  from the watch's `.part` offset afterwards, the Data Layer channel just stops writing
  and stays open - so debug commands issued mid-deploy no longer race the APK for the radio. Watch for
  `WatchAppInstallService: Sent watch-app install request...` /
  `...Failed to install...` in logcat, and cross-check `PhoneMessageRouter`'s
  `Watch app-management result on /app/install/result` line on the phone.
- Why this exists instead of just fixing watch-side adb: even when the watch *is* on
  adb, that's an unreliable dev-loop dependency (off-wrist Wi-Fi drop, wireless
  debugging toggling off). The Bluetooth link is the same one the production
  phone↔watch sync depends on, so debug tooling that reuses it is testing the real
  path instead of a parallel one.
