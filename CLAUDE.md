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
system Settings, otherwise the start is refused and logged. Watch-recorded fixes arrive on
`/workout/route` (JSON array, `WorkoutPayloads`); they must arrive before the EXERCISE sample to land on
its record. Route points follow the raw retention; MCP tool `get_exercise_route` pages them.

### watch-app measurement windows and the off-body gate

`VendorWindowSampleSource` (3 min every 30 min, paced by `AlarmWindowScheduler` - an
`ELAPSED_REALTIME_WAKEUP` alarm, never a coroutine `delay`, whose monotonic clock stops while the
watch's CPU is suspended) measures `AggregatorGroup`s, each behind a
`WindowGate`: the body group (SpO2, RMSSD, `psp_rr` respiration rate, skin temperature) is gated by
`OffBodyDetector` (`TYPE_LOW_LATENCY_OFFBODY_DETECT`, checked at window start and end; unknown state
= worn); the environment group (NST112 ambient temperature, `mspc110` pressure, `opt3007` light, all
`TimeWeightedMeanAggregator`s) always runs. RESPIRATION_RATE goes to Health Connect
(`RespiratoryRateRecord`); BAROMETRIC_PRESSURE and AMBIENT_LIGHT are history-only like ambient
temperature. SpO2 has its own body group with a `StillnessQualifier` (`WindowQualifier`: listens to
the accelerometer for the whole window, drops the group's results if >15% of events deviate >1.5 m/s²
from gravity - PPG reads motion as desaturation) and reports the median of the converged readings,
not the latest one. `psp_rr`'s layout is unconfirmed (a probe saw ~64 in values[0]), so the aggregator only
keeps 4-40 breaths/min.

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
  silent wait is expected, not a hang. Watch for
  `WatchAppInstallService: Sent watch-app install request...` /
  `...Failed to install...` in logcat, and cross-check `PhoneMessageRouter`'s
  `Watch app-management result on /app/install/result` line on the phone.
- Why this exists instead of just fixing watch-side adb: even when the watch *is* on
  adb, that's an unreliable dev-loop dependency (off-wrist Wi-Fi drop, wireless
  debugging toggling off). The Bluetooth link is the same one the production
  phone↔watch sync depends on, so debug tooling that reuses it is testing the real
  path instead of a parallel one.
