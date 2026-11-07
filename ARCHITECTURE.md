## Reading Code & Architecture

## Subprojects

This repo is a monorepo of independent apps that together sync data between a phone,
a TicWatch Pro 5 Enduro (Wear OS), and a web dashboard, and let the phone deploy/remove
watch-app builds onto the watch.

- `backend/` — Spring Boot (Kotlin) cloud history server (PostgreSQL, Docker). Own Gradle module,
  included from the root `settings.gradle.kts`. Package root: `org.sand.ticwatch.backend`. See
  "Cloud storage" below.
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

### UI state: observable holders, single source of truth, nothing blocking on the main thread

Every screen renders one `StateFlow<UiState>` from a state holder extending `ui/UiStateStore` (phone; mirrored in
watch `ui/`): `launchIo` runs each data-source call on the injected IO dispatcher and reduces only the result into the
state on the screen's scope (`rememberStore` = composition scope, cancelled with the screen; watch `MainActivity` uses
`lifecycleScope`); `observe` listens to a source flow. Composables never call stores/DBs/prefs themselves and keep only
ephemeral widget state (dialog open, menu expanded). Read-only screens use `ReloadingStore` (load once via `LOAD_ONCE`,
or re-load on each emission of a change flow). Data changes are pushed, not polled: `AppContainer.historyChanges`
(fired after every stored sensor push via `watchStatePublishers`) and `bloodPressureChanges` (BP log listener) are
`DataChangeSignal`s; combine them with `anyChangeOf`. Holders: `CardioAdvisorStore`, `BloodPressureStore`,
`HistorySettingsStore`, `ProfileSettingsStore`, `McpAgentStore`, `WatchAppUpdateStore`, `SleepJournalStore`, `WatchWorkoutStore`, `DeviationEpisodesStore`, watch `WatchDashboardStore`.
New screens follow the same pattern; a new writer of displayed data fires (or adds) a signal.
Phone theme: every activity's `setContent` wraps in `ui/TicWatchTheme` (light/dark Material 3 scheme following the system dark
theme, over the `DayNight` window theme); draw with `MaterialTheme.colorScheme`, not `Color.Black`/`White`, except on
fixed-colour surfaces (ECG paper, coloured badges).

### Localization (English / Russian / Polish, one language for both apps)

Every user-visible string lives in `res/values{,-ru,-pl}/strings_ui.xml` (+ `plurals_ui.xml` on the phone) of each app -
add a new string to all three files, with the same format specifiers. Compose reads them with `stringResource`;
non-UI code (engines, analyzers, stores) returns `text/UiText` (resource id + args, nested `UiText` args resolved
first) or an `@StringRes` enum label, resolved only where shown (`UiText.asString()` in Compose, `TextResolver`
elsewhere). Payloads the watch displays as-is (BP history/status, health scores, space-weather labels) and the
doctor's report resolve through `AppContainer.appTextResolver` (app language); MCP tools use
`HistoryToolFormat.text` over `mcpTextResolver`, which is pinned to English so the agent's vocabulary stays stable.
Settings > Language (`AppLanguageSettings`: System/English/Русский/Polski) sets the per-app locale through AppCompat
(`AppLocalesMetadataHolderService` autoStore below API 33; `MainActivity` is an `AppCompatActivity`) and writes the
tag into the synced `WatchSettings.language`; the watch's `WatchLanguageFollower` applies it with `LocaleManager`.
Watch code outside Compose uses `text/Strings` (`ContextStrings`). Watch-face units ("BPM", "°C") stay universal.

### Cloud storage (home server, all watch data)

`backend/` (`cloud/`): Spring Boot 4 + JdbcTemplate + Flyway (`db/migration/V1__cloud_history.sql`) on PostgreSQL 17,
run with `backend/docker-compose.yml` in WSL's Docker (`backend/deploy-wsl.ps1` copies the context to
`~/ticwatch-cloud` - E: isn't mounted in WSL - and runs `docker compose up -d --build`). Port **8766** (job_searcher
uses 8080/8765/5432); Postgres isn't published. WSL is in mirrored networking with its firewall on, so 8766 needs a
Windows inbound rule **and** a Hyper-V rule (like `WSLJobSync8765`) to be reachable from the phone - `backend/open-firewall.ps1` (elevated, once)
adds both. REST (`CloudApi` in
both apps, keep in sync): `GET /api/v1/ping` (`{"service":"ticwatch-cloud"}`), `POST/GET /samples` (codes =
`SensorTypeCodes`/`SampleSource`, idempotent via a unique (type, recorded_at, source, ended_at, value) NULLS NOT
DISTINCT index), `GET /rollups` (FIVE_MINUTES/HOUR/DAY computed from raw rows, DAY at local midnight of `zone`),
`POST/GET /route-points`, `POST /ecg-recordings` (strip as JSONB). `X-Device-Id` is stored, never filters reads.
Security for later: `TICWATCH_CLOUD_API_KEY` set -> `ApiKeyFilter` requires `Authorization: Bearer` (ping exempt);
the phone sends `CloudSettings.apiToken` when set (no UI yet) - plain HTTP (`usesCleartextTraffic`) for now.

Phone `cloud/`: discovery as in job_searcher - `SubnetCloudServerLocator`: last host -> Settings' manual host ->
/24 scan (300 ms TCP connects, 48 parallel) confirmed by ping. Upload = outbox + acknowledgement: `sensor_history.cloud_synced`
(schema v8, partial index on unsynced ids), `exercise_route_point.cloud_synced`, `sleep_session`/`sleep_stage.cloud_synced` (v9; `SqliteSleepOutbox`,
sent as SLEEP/SLEEP_STAGE samples, the unique index absorbs repeats of the raw rows), `cardio.db` v5
`ecg_recording.cloud_synced`; each store implements `CloudOutbox<T>`, `CloudUploadChannels` wraps them as
`OutboxUploadChannel`s (rows marked only when the answer's `received` equals the batch size; both servers answer after storing, and neither ever deletes rows) - add new watch data the same way. `sensor_rollup` has no flag: derived, the server computes its own from raw rows.
`CloudSyncEngine` (one per process, mutex) drains all channels and writes `CloudSyncStatus` (process-wide prefs
store). `CloudSyncWorker`: hourly on unmetered network, `requestSoon` (KEEP, +1 min) via `CloudSyncTrigger` after
every stored push (in `watchStatePublishers`), BP log change and stored strip, `runNow` from the title bar/Settings.
Retention: while cloud storage is on, no retention purge deletes a row the server hasn't acknowledged, however long it is away
(`SettingsCloudRetentionHold` -> `purgeReceivedBefore(cutoff, keepUnsynced)`, same for routes, sleep, ECG), so retention can be 1 day; with cloud storage off the retention applies to all rows. Deliberate deletes (Clear history, deleting a reading/strip, replacing a night) don't wait for the server.
Reads: `AppContainer.historyQueries` = `CloudBackedSensorHistoryQueries` over the local store - raw rows older than
the oldest local row of the requested types and rollup buckets older than their tier's retention come from the
server (`HttpCloudHistoryReader`, last host only, 1 min back-off after a failure), so every screen/deriver/MCP
tool reads as if the phone kept everything; writers, erasers, backfill and sleep-stage/size readers stay local.
Screens never wait for it: `UiStateStore.launchLocalFirstRead` (`LocalFirstCloudReads` context mark, used by `ReloadingStore`
and the BP/Advisor stores) computes from local data plus what `BackgroundCloudHistory` holds; a miss fetches in the background (2 at a time,
whole local days, LRU cache), and `AppContainer.cloudHistoryArrivals` makes the screens re-read when rows arrive - add it to a new
screen's `anyChangeOf`. Unmarked readers (derivers, which store results; MCP tools, the report, the widget, watch publishers) still wait for the server.
Title bar: `CloudSyncIndicator` left of the battery (off / not synced / syncing (pulsing) / synced; tap = sync now);
Settings > Cloud storage (switch, status, manual address, "Sync now"). Not yet read back from the cloud: route
points (MCP `get_exercise_route`), ECG strips, `coverage()` (a reinstalled phone lists only local metrics).

### Cloud server (Go, netbook)

`cloud-server/` (not a Gradle module; own `go.mod`, see its README): Go + `net/http` + pgx re-implementation of `backend/`
for the home netbook (`ssh netbook` from WSL, 192.168.1.81, ASUS X101H, 2 GB RAM, HDD, antiX 32-bit i686 - no JDK 25, hence Go). The phone
needs no change. `deploy-netbook.sh` (WSL, from `/mnt/e/...`): cross-compiles a `linux/386` scratch image, `docker save | ssh ... docker load`, copies
`docker-compose.yml` to `~/ticwatch-cloud` and runs Debian's `docker-compose` 1.29 (file format 2.4; compose v2 has no i386 build).
`postgres:17-alpine`, tuned for the box in the compose file, `fsync`/`synchronous_commit` stay on (the phone marks rows synced
on the answer), port 5432 unpublished. Port **8766**, env `TICWATCH_CLOUD_PORT`, `TICWATCH_DB_URL` (pgx DSN, unlike the
Kotlin server's JDBC URL), `TICWATCH_DB_USER`, `TICWATCH_DB_PASSWORD`, `TICWATCH_CLOUD_API_KEY`.

Power policy (`netbook-power/`, installed by `deploy-netbook.sh`): closing the lid powers the netbook off cleanly (`lid.sh.pre` -> `/etc/acpi/local/`, power button to start it again, the phone's outboxes wait; deliberately no suspend, also not on idle); `battery-guard` (cron, every minute) stops the containers cleanly below 10% battery without mains; the netbook stays on and the containers stay stopped until `sudo docker-compose up -d` in `~/ticwatch-cloud` or the next boot. At boot `cloud-autostart` (cron `@reboot`, waits for Docker, starts it if needed) runs `docker-compose up -d` unless `battery-guard --check` reports a low battery without mains.

Layout: `internal/platform/{auth,db,migrate,httpx,app,jobs}` shared, `internal/apps/ticwatch` everything TicWatch. Each app
implements `app.App` (routes, migrations, public paths; optional `JobProvider`, none yet) and has its own Postgres schema
(`ticwatch`: same tables as `V1__cloud_history.sql`, tracked in `ticwatch.schema_migrations`). Later apps, per-app keys
(`auth.KeySource`) and nightly jobs (`jobs.Job`: cheap, incremental, no full-history scans on request paths) plug into these seams.
JSON doubles go through `JavaDouble` so they print as Jackson's (`72.0`, `1.0E7`).
Stats page `GET /ticwatch` (public, no key - LAN only; Go server only): database size, rows/size/time span per table and rows per sensor
type (`HistoryStatsStore` -> `StatsRepository`, full scans on each load; names from `sensortypes.go`, mirror of `SensorTypeCodes` - keep in sync).

Contract sync: `backend/.../CloudApi.kt`, phone-app's `cloud/CloudApi.kt` and `cloud-server/internal/apps/ticwatch/contract.go`
(+ `dto.go`) must stay in sync while both servers exist. `scripts/contract-diff.sh` runs both on fresh databases and diffs responses, stored rows
and schema (rebuild `backend-cloud:latest` first if `backend/` changed). `scripts/migrate-from-wsl.sh` shows row counts; `--apply` copies
the WSL database (schema `public` -> `ticwatch`). Since 2026-10-30 the netbook is the live server: history migrated, phone uploading to it, WSL `backend-cloud-1` stopped.
Second hosted app: `internal/apps/myapplication` (`/myapplication/...`, schema `myapplication`) - the exchange of the MyLibrus Android apps (parents publish and read each other's state for the children's accounts the admin links them to, `parent_account`; a child reads its one account), with its own device-role auth and admin page (`/myapplication/admin`, password `MYAPPLICATION_ADMIN_PASSWORD`, kept in the netbook's `~/ticwatch-cloud/.env`, created by `deploy-netbook.sh`). Its contract is mirrored by `CloudContract.kt` in the MyLibrus repo; it shares nothing with `ticwatch` but the platform.
Known limit: `/rollups` aggregates raw rows on each request (DAY over 340k rows of one type: ~10 s on the Atom, linear in rows); the intended fix is an incremental rollup table fed by a `jobs.Job`.

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
writes per sensor type and marks rows `DELIVERED`/`FAILED`. Retention (1/3/7/15/30 days,
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
the night is over. A wake (sensor's 7, or any non-sleep event) stays in the session as an AWAKE stage while <= 30 min
at night (begins 21:00-10:00) / 5 min by day (`NightAwareWakeMergePolicy`); a longer one ends it where the wake began.
Sessions need 10 min of actual sleep. The sensor often just stops when the wearer gets up, so while a session is open
an `AlarmWindowScheduler` check (5 min, flushes the hub's batch first) ends it at the last sleep event on > 2 h silence,
10 min off the wrist (`OffBodyWakeEvidence`) or > 300 steps in 5 min (`WalkingWakeEvidence`). The open session is kept
in `SharedPreferencesOpenSleepSessionStore`, so a restart resumes the night or closes it on the first check. Phone
safety net: `OrphanSleepSessionDeriver` (at most every 15 min, last 7 days) turns stored session-less stages (>= 15 min
asleep, gaps <= 30 min, quiet for 3 h) into a normal SLEEP sample, so a lost one still reaches `sleep_session`, Health
Connect (client record id `sleep_session_<start>`, a re-send upserts) and the night derivers; `SleepSessionWriter` lets
a newer session supersede any it overlaps. A wrongly recorded night is repaired with the
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
`LocationManager` GPS, 10 s) during EXERCISE - only while the phone isn't tracking it: `PhoneRouteTrackAnnouncer` sends `/workout/phone-track` (`{"active": true}`, every 2 min while it gets fixes within 30 m) and the watch's `LiveSyncLease.phoneRouteTrack` (5 min, checked on an alarm) keeps its GPS off; `ExerciseRouteFactory` still uses one source per session, so a phone left behind mid-workout can lose that part - and sends fixes on `/workout/route` (JSON array,
`WorkoutPayloads` in `watch/route/`) in batches of 60 plus the rest at workout end; needs
ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION on the watch (MainActivity asks, background ones
sequentially). They must arrive before the EXERCISE sample (next sync flush: every 30 min or at 400 buffered samples) to land on
its record. Route points follow the raw retention; MCP tool `get_exercise_route` pages them.

### watch-app automatic walk workouts

`watch/autoworkout/`: `AutoWorkoutController` (run from `SensorCollectionService`) watches the platform step
counter (`StepCounterSensorSource`, wake-up variant, one registration shared by all listeners, 60 s batches - no wakeups while still) through
`BriskWalkDetector` (>= 90 steps/min over a 1 min window, 5 min pauses tolerated, not counted as walking). After 5 min of brisk walking it
starts a Health Services WALKING exercise (`HealthServicesWorkoutSession`, `ExerciseClient`, GPS left to
`WatchRouteRecorder`) - immediately in AUTO mode, after the notification's "Start" in ASK mode (default; the
dashboard card cycles Off/Ask/Automatic, `SharedPreferencesAutoWorkoutSettings`) - and ends it after 5 min below
60 steps/min or on the ongoing notification's "End" (service intents `ACTION_START/END_AUTO_WORKOUT`). The session keeps an
`ExerciseUpdateCallback` registered (also after adopting a running workout): without one Health Services ends the exercise
itself (`AUTO_END_MISSING_LISTENER`) - every auto walk until 2026-11-03 stopped ~5 min after its start. It never
starts while another app's workout runs and acts on each brisk stretch once. The exercise flips the passive state
to EXERCISE, so the existing session/route/phone pipeline records it; `AutoWorkoutStartLedger` backdates that
session's start to where the walk began and `sessionType` sends that session as AUTO_EXERCISE (shown as "Auto walking"; same pipeline as EXERCISE). Re-checks run on an `AlarmWindowScheduler` every minute only while a
stretch or workout is open.

Remote control from the phone: `/command/start_workout` and `/command/end_workout` (`{"request_id"}`) -> `WatchMessageRouter` -> the same service intents as the notification's buttons (`WorkoutCommandRequester`), so a remote start is the same walk workout: AUTO_EXERCISE, ignores the Off/Ask/Automatic mode and ends itself after 5 min below 60 steps/min. Phone: `WatchWorkoutCommands` (`AppContainer.watchWorkoutCommands`), used by the Metrics tab's `WatchWorkoutCard` and MCP `start_workout`/`end_workout`. `WorkoutCommandResponder` answers on `/command/workout/result` (`{"request_id", "status"}`, through `WatchReplies`) with what `AutoWorkoutController` did: STARTED, ALREADY_RUNNING, OTHER_APP_RUNNING (Health Services' `exerciseTrackedStatus`, checked on each command), ENDED, NOT_RUNNING or FAILED; no answer within 1 min is an error, though the command may still have run. The card shows "in progress since HH:mm" (and disables Start) from `WatchWorkoutActivityStore` (process-wide prefs, `route/WatchWorkoutActivity.kt`), fed by `/workout/state` through `StateTrackingWorkoutRouteRecorder` and by command answers (`WatchWorkoutOutcome.runningAfter`); an "active" older than 6 h counts as over.

### watch-app adaptive polling and Baseline Snapshot

`watch/sensors/adaptive/`: every collected sample (`SensorCollectionService.onCollected`) goes through
`AdaptiveSensorScheduler`'s `AdaptiveTrigger`s (Strategy, one alert state per `TriggerId`): `RestingHeartRateTrigger`
(HR outside the phone's personal corridor of the current state - `/insights/hr-corridor`, `WatchHeartRateCorridorPublisher`, whole bpm, else 100 / 50 - while no steps for 2 min and no workout -> HEART_RATE continuous, HRV + SpO2 every 5 min),
`DesaturationTrigger` (SpO2 < 94 -> SpO2 again 60 s after the window), `SkinTemperatureRiseTrigger` (>= 1 °C over the
median of the in-memory 6 h trend, awake only -> every 5 min). `MonitoringProfile` per `MeasurementGroup`: IDLE (30 min)
-> ALERT on an abnormal reading -> RECOVERY on the first normal one (boost interval x2) -> IDLE after 10 min of normal
readings; an ALERT without readings for 15 min starts recovering. Decay is lazy (on samples and reads) - no timer of its
own. IDLE cadences come from `BaseCadence` (`SettingsBaseCadence`): 30 min, HRV by the synced `hrv_interval_minutes` (30/15/10/5, phone Settings > Watch features). `VendorWindowSampleSource` paces each `AggregatorGroup` (now keyed by `group`) by `WindowCadence.intervalFor`,
counted from its last window's end; groups due within 5 min join a starting window; `cadence.changes` interrupts the
alarm wait. `SensorWindowMeasurer` (extracted) does the actual window. `BoostedHeartRateSampleSource` runs the Measure
API (thinned to 1 per 10 s) only while HEART_RATE isn't IDLE. Add a trigger by registering it in the service.

Baseline Snapshot (`watch/snapshot/`): `BaselineSnapshotCapturer.captureFullSnapshot()` - worn check, 10 s
accelerometer + gyroscope stillness probe, then all window groups in one 3 min window + live HR median. Started by the
watch's "Snapshot" page or the phone's `/command/capture_snapshot` (`{"request_id"}`; the router forwards it to the
service as `ACTION_CAPTURE_SNAPSHOT`). `SnapshotController` sends `/snapshot_progress` (`{"request_id", "stage", "polled", "answering"}`, phone requests only, conflated, on its own coroutine)
as each `SnapshotStage` starts and whenever a metric's sensor first answers (`WindowObserver`; all are polled at once, not in turn), flushes the buffer for at most 45 s (past that the flush carries on alone) and sends `/snapshot_result` (status, samples, HR in the BP
request keys); if the phone is unreachable the samples go into the regular sync unflagged. The phone's button shows the stage, its elapsed time and the answering/awaited metrics
(`ProcessSnapshotResults.progress`, stages only move forward; `COMMAND_ACCEPTED` = the watch ACKed the command); past the timeout + 1 min it is released with a message naming the stuck stage -
link sends block on an ACK that cancellation can't interrupt, so such calls are abandoned, not awaited. Phone (`phone/snapshot/`):
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
sensor presence decides. Passive HS data, environment window group, sync and wire format are provider-independent. Passive HR once stalled for 8.5 h while steps kept coming (2026-11-03): `HealthServicesPassiveSampleSource` re-registers when steps arrive 20 min after the last HR while worn.
Add a vendor (e.g. Samsung SDK) as another provider + factory rule. Mobvoi `uses-permission`s are inert elsewhere.

### watch-app measurement windows and the off-body gate

`VendorWindowSampleSource` (3 min every 30 min, paced by `AlarmWindowScheduler` - an
`ELAPSED_REALTIME_WAKEUP` alarm, never a coroutine `delay`, whose monotonic clock stops while the
watch's CPU is suspended; exact allow-while-idle via `USE_EXACT_ALARM` + `WakeAlarmReceiver`/`WakeAlarms`, since Doze held
plain `setWindow` alarms 4-5 h on still nights) measures `AggregatorGroup`s, each behind a
`WindowGate`: the body groups (SpO2; RMSSD + breathing rate; skin temperature) are gated by
`OffBodyDetector` (`TYPE_LOW_LATENCY_OFFBODY_DETECT`, checked at window start and end; unknown state
= worn) and, except skin temperature, closed while asleep (the night monitor below takes over); the environment group (NST112 ambient temperature, `mspc110` pressure, `opt3007` light, all
`TimeWeightedMeanAggregator`s) always runs. RESPIRATION_RATE goes to Health Connect
(`RespiratoryRateRecord`); BAROMETRIC_PRESSURE and AMBIENT_LIGHT are history-only like ambient
temperature. SpO2 and RMSSD/breathing each have a `StillnessQualifier` (`WindowQualifier`: listens to
the accelerometer for the whole window, drops the group's results if >15% of events deviate >1.5 m/s²
from gravity - PPG reads motion as desaturation). To spare the PPG both have a `startGate` (<= 10 steps in the last minute; a snapshot ignores it), and `alreadyRejects` switches a group's sensors off as soon as that 15% can no longer be met. SpO2 reports the median of the converged readings,
not the latest one. Breathing rate comes from the `psp_rri` RR series (`RriRespirationRateAggregator` ->
`RespiratorySinusArrhythmiaEstimator`: 4 Hz resample, detrend, Hann, strongest 0.1-0.5 Hz peak if >= 3x the
band mean); `psp_rr` read all zeros, so it isn't registered anymore.

Heart-rate sensors (probe 2026-10-27): passive Health Services HR is the background source, the Measure API runs
only while `MainActivity` is visible, `ExerciseClient` during auto workouts. Platform `TYPE_HEART_RATE` (21) is
`psp_hr`, the same vendor pipeline - unused. Undecoded, recorded raw for decoding **only while the synced "Log capture" setting is on** (`MobvoiDiagnostics`, also `VendorSensorRecorder`'s `sleep_sensor.log`/`temperature_sensor.log`): `psp_lphr` (33171109) by the night
monitor (`RawEventRecorder` -> `heart_rate_sensor.log`, from the next night), and `mobvoi_hr_alert` (33171609,
wake-up "Inactive HR Sensor") by `HeartRateAlertSampleSource` (`heart_rate_alert.log`) and sent
as HEART_RATE_ALERT (value = raw values[0]; history only, Health Connect has no alert record). Both logs come
with `DEBUG_PULL_WATCH_SLEEP_LOG`.

Night breathing monitor: `VendorSleepStageSource` feeds `TrackedSleepState` (open session, <= 2 h since the
last sleep event); while asleep and worn `NightBreathingSampleSource` keeps `psp_spo2`/`psp_rri`/accelerometer
registered all night (re-registering restarts SpO2's ~2 min convergence), batched in the hub FIFO when its capacity holds 1 min of their events
(an alarm every minute flushes it and re-checks sleep/wear, the epoch closes after a final flush, CPU otherwise suspended); else, or once a batched sensor
delivers nothing over a worn epoch, under a non-refcounted partial wake lock (the former battery cost; the synced `night_monitor_intermittent`, default off, pauses sensors and lock 10 min after each epoch), and every 5 min emits SpO2/RMSSD/breathing rate as epoch intervals, completed
`DesaturationDetector` events (OXYGEN_DESATURATION: >= 3 points below the 2-min median for >= 10 s) and
SNORING (from `MicrophoneSnoreMeter` listening 20 s at the start of every second epoch, stamped over both epochs so the phone's minutes add up: 8 kHz frame
features only, nothing stored; `SnoreDetector` wants loud low-pitched 0.3-3.5 s bursts repeating every 2-9 s).
Microphone needs RECORD_AUDIO and the FGS `microphone` type, which `SensorCollectionService` adds in
`onStartCommand` - only granted when started from `MainActivity`; after a boot start snoring is skipped.
Sensors and wake lock stop within 15 s of waking up or taking the watch off (an epoch that ends off the wrist reports nothing about the body).

Night sync: the night monitor makes passive HR arrive at 1 Hz (day: ~1 per 10 s), which alone hit the 400-sample flush threshold every ~6 min. `SleepHeartRateThinner` (after `onCollected`, so the watch's own consumers still get every reading) sends one 30 s median instead while asleep with no workout and no live sync, so night flushes fall back to the 30 min alarm, and a finished SLEEP session flushes at once (`SensorSyncSender.flushesAtOnce`). Phone readers of HR must cope with 2 readings/min at night: `RestingHeartRateEstimator` wants 15 per 10 min, `ActivityIntensityDeriver` skips such minutes.

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
(Banister TRIMP) and HEART_RATE_ZONE_1..5 minutes (Karvonen, Tanaka max HR, needs birth year) per workout, plus HEART_RATE_ZONE_0 (code 50: below zone 1, so the zones add up to the workout; counts as easy).
Computed on read from rollups: `HrvStatusClassifier` (7-day nightly RMSSD mean vs 28-day mean ± 0.75 SD;
Low < mean - 1.5 SD, Poor < 20 ms), `TrainingLoadBalanceCalculator` (acute 7-day sum / chronic 28-day weekly
mean; a ratio > 1.5 reads as Optimal when acute load < 150 TRIMP or > 85% of the 7-day zone minutes are in zones 1-2, and the gauges use `gaugeRatio`, capped to that band, also sent as `load_gauge_ratio` to the watch), `DailyStressCalculator` (5-min RMSSD buckets per `StressLevel`, sleep and walking buckets excluded),
`CircadianLightExposureCalculator` (>= 1000 lux 06-12 local, each window reading covers <= 30 min).
`HistoryHealthScoresSource` composes them for the Metrics tab and MCP (`get_sleep_score`, `get_hrv_status`,
`get_training_load`). The "Training load" card draws the 7-day zone-minute sums (`dashboard/HeartRateZoneChart`, Garmin-style) and opens `WorkoutJournalSheet` (`HistoryWorkoutJournal`: the same 7 days of EXERCISE/AUTO_EXERCISE with type, time, distance = longer of the GPS track and the watch's DISTANCE intervals, zones). Tapping the Metrics "Sleep score" card opens `SleepJournalSheet` (`SleepJournalStore` pages 14-day
spans backwards over `LocalSleepJournal`): sleeps grouped by wake-up day, hypnogram and stage totals, score from the HOUR
rollups (365 days). It reads `sensorHistoryStore` directly, so unlike `historyQueries` it never calls the cloud server. Each entry
also carries `SleepAdvancedInsights` from a local-only `SleepAdvancedInsightsReader` behind `SettledNightInsightsCache` (nights ended > 24 h ago are kept, since live sync re-reads the
latest span every few seconds); `SleepInsightsDetails.kt` renders them on the journal card and, shortened, on the Metrics "Sleep score" card (which also carries the night's duration, stages, ODI, snoring and the SpO2 trend), whose `SleepInsight.advanced` comes from the regular cloud-backed reader. Snoring and ODI
come from raw rows, so older nights show only what the rollups still give (recovery) and the stages (fragmentation).
After every stored sensor push `WatchHealthScoresPublisher` sends them (only when
changed) on `/insights/scores` (`HealthScoresPayload`, mirrored on the watch); the watch keeps the last one in
`SharedPreferencesHealthScoresStore` and shows "Sleep score" and "Training load / HRV / stress / daylight" pages.

### phone-app fatigue index

`insights/FatigueIndex.kt`: `FatigueIndexCalculator` (pure, heuristic, 0..100%) = weighted mean, renormalised over the
components with data, null below half the total weight: physical load 30% (max of 24 h steps / 20k and TRIMP / 200),
autonomic strain 30% (max of RMSSD drop below the same hour's 14-day mean, full at 50%, and the absolute penalty 30 -> 25 ms),
pulse debt 20% (resting HR above the hour's norm, full at +20 bpm), recovery deficit 20% (max of sleep score under 70,
full at 40, and vagal ratio under 1.0, full at 0.7). `FatigueLevel` bands 30/60/80/100. `HistoryFatigueIndexSource` reads
the rollups plus the unstored push; resting HR = lowest 5-min mean of the last hour among buckets of <= 200 steps;
`CircadianNorm` is shared with the Advisor. `FatigueIndexDeriver` (last in the deriver list) stores FATIGUE_INDEX (code 47,
history only, no schema change) at most every 30 min. Tapping the Metrics card opens `FatigueHistorySheet`: seven 00:00-23:59 charts by hour (fixed 0-100 axis), newest first; a span of 7/14/21/28 days overlays 1/2/3/4 consecutive days on each chart (14 days: today + yesterday, the two days before them, ...), one colour per position; a tap or horizontal drag on a chart moves a vertical line that shows its time and each day's value (`TrendChart.formatScrubValue`, `Trend.valueAt`: along the drawn line, none before the first or after the last point). From the stored points' HOUR rollups (`RollupFatigueHistory`), so days before the deriver ran stay empty and components have no history. Computed on read for `HealthScores`: Metrics card and, as
`fatigue_percent` on `/insights/scores`, the watch's "Fatigue index" page. The Advisor tab's gauge shows its inverse as load readiness on its inner ring, cardio readiness on the outer one (`FatigueIndex.loadReadinessPercent`, `CardioReadiness.fatigue`), with the components below; `/insights/readiness` carries it (`load_readiness`, `load_level`, `fatigue_label`, optional) for the watch dashboard's first page and the readings tile (same two rings).

### phone-app deviation episodes (human in the loop for calibration)

`deviation/`, preliminary, observe only: polling and the watch's triggers don't depend on it. `DeviationScanWorker` (WorkManager, unique KEEP, +30 s after each stored push through `watchStatePublishers`, battery not low, never retried) runs `DeviationEpisodeScanner`, so receiving a push does no scanning: `NewHistoryRows.storedAfter` finds where the HR/workout rows stored since the last finished scan lie in time (`DeviationScanCursor`, a `sensor_history` id, so late batches count; a failed run leaves it in place; first run at most 24 h back, later ones 7 days), and that range + 30 min before it is scanned on the local store; `RestingDeviationDetector` (pure) finds >= 3 min of watch HR outside the `HeartRateCorridors` of the reading's state while at rest (<= 30 steps in 5 min, interval samples counted by their overlap with the window, not within a workout + 30 min), so delay = sync delay, up to 30 min, for now. Corridors (`RollupHeartRateCorridorSource`, cached 1 h, one per process via `DeviationModule.corridors`): 14 days of 5-min rollups at <= 30 steps, split awake/asleep by `sleep_session`, median + max(12, 2 robust SD), no hour-of-day split (too few days to trust one); fixed 90/52 awake, 85/48 asleep until 288/96 buckets exist; the low edge never stricter than the fixed one. Ignored: the first 10 min after a >= 10 min HR gap (watch put back on) and, while > 500 steps in 30 min, a HR that isn't climbing >= 8 bpm above its low since the walk (recovery). Short shallow ones (< 5 min and peak < edge + 15) are stored `minor`: uploaded, never listed or asked. Each episode stores its edge, baseline, bpm-minutes outside, `worn_since` and a `DeviationContext` (steps 30 min before, RMSSD of the hour before, last sleep score, fatigue, last intake within 48 h; `HistoryDeviationContextSource`), cardio.db v7 (AutoMigration), server `V3__deviation_context.sql`; answers may add an optional `DeviationIntensity`. `cardio.db` v6 `deviation_episode` + `deviation_episode_reason`: a candidate overlapping an episode extends it, one within 30 min extends an unanswered one, so a re-scan is safe; a workout's sample arriving later marks the unanswered episodes it covers `discarded_at` (hidden, kept for the server). `DeviationQuestionAsker` + `DeviationQuestionPolicy`: notification only 07-22, at most 3 a day, episodes of the last 24 h; a single waiting episode gets up to 3 one-tap answers among reasons used before (`DeviationAnswerReceiver`). Drawer screen "Deviations" (`DeviationEpisodesStore` over Room's flow, since each component has its own `AppContainer`) lists the unanswered ones (an answered one disappears, its answer can't be changed there), with reason chips, most used first; all answers go through `DeviationAnswers`. `DeviationEpisode.calibrationUse`: no answer = waiting, only `NOTHING_SPECIAL` includes it. Upload: the last channel of `CloudModule.channels`, `POST /deviation-episodes` on both servers (`V2__deviation_episode.sql`, upsert by the phone's `uid`, reason codes as a JSONB array, code meanings in the migration's comment), unanswered and discarded rows too, for later analysis; there is no read endpoint, it is meant for SQL. A row is pending while `revision != synced_revision`; every change bumps `revision` and an answer also requests a sync. The server must have the endpoint before the phone sends: a 404 fails the sync round. Nothing consumes the answers yet: no personal corridors, no customisable button set.

### phone-app space weather (geomagnetic Kp / solar flux)

`spaceweather/`: `SpaceWeatherRefreshWorker` (WorkManager, every 3 h, network required; force with the
`DEBUG_REFRESH_SPACE_WEATHER` broadcast) fetches NOAA SWPC's free products - `noaa-planetary-k-index-forecast.json`
(3-hour Kp: ~7 days observed/estimated + 3 days predicted) and `27-day-outlook.txt` (daily largest Kp, Ap, F10.7
flux) - and `SpaceWeatherAssembler` joins them into today + 6 local days (the Metrics card charts them as 7 Kp bars) (outlook Kp only for days the 3-hour slots
don't fully cover). `GeomagneticLevel` = NOAA G-scale on Kp rounded (4.67 = G1); `SolarActivityLevel` by F10.7.
Only the latest fetch is kept (`SharedPreferencesSpaceWeatherStore`, `SpaceWeatherPayload` JSON), no history table.
Shown as the Metrics tab's last card, MCP `get_space_weather` (incl. all 3-hour slots), and sent on
`/insights/space-weather` to the watch's "Geomagnetic" page (`PhonePayloadStores.spaceWeather`). The router runs
every `WatchStatePublisher` in `AppContainer.watchStatePublishers` after each stored sensor push; those that send to the watch sit behind `CoalescingWatchStatePublisher` (deduplicated, once per burst, over one held socket; `LinkHold` is counted on the phone). A payload with its own timestamp needs a `TimestampedPayloadRefresh`, or it never deduplicates.

### phone-app blood pressure (calibrated cuffless estimate)

Dashboard tab "Blood Pressure" (`BloodPressureScreen`) over `health/BloodPressureMonitor`. Calibration = a cuff
reading the user types in plus the wrist signals at that moment (live HR median, else the day's resting HR; RMSSD,
skin temperature), kept as `BloodPressureCalibration` by `SharedPreferencesUserProfileStore`
(`BloodPressureCalibrationStore`), stale after 30 days. `BloodPressureEstimator`: `SYS = base + 0.85*dHR +
14*(1 - RMSSD/RMSSD_base) - 1.2*dT_skin` (±35) + offset, `DIA = base + 0.3*dHR - 1.5*dT_skin` (±15) - coefficients not fitted to
the user, trends only. Offset: `CuffBiasSystolicOffset` (0..15 mmHg, persisted) moves by the mean systolic delta (estimate minus cuff) of the last 5 `CalibrationAccuracyCalculator.comparisonsBeforeCuff` pairs (estimate up to 30 min before the cuff; a later one may be calibrated from it) made under the current offset, so saved estimates, which already include it, can't cancel it. Refused when the last 5 min show > 40 steps/min (`WristSignalsReader`; every measurement
request syncs the watch first, so only a failed sync can leave recent walking unstored). Saved only on the user's "Save" as BLOOD_PRESSURE_DIASTOLIC +
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
20 s gyroscope probe (`GyroscopeStillnessQualifier` via `SensorQualifierProbe`, <= 20% moving events) accepts; every estimate is saved to
history. Sleep: the separate synced `night_blood_pressure` (phone Settings > Watch features, default off; "Auto at rest" no longer measures in sleep)
makes `AutoBloodPressurePlanner` measure inside a sensor-detected sleep session (`TrackedSleepState.sessionChanges`, not the clock) every 30 min (clinical ABPM night cadence), every
10 min in the 90 min before the usual wake-up (median of the last 14 nights >= 3 h, `MorningWindows` over `SharedPreferencesWakeTimeHistory`, fed by
`SleepTrackingSessionStore`, a decorator over the open-session store that also gives the current stage), and, awake with the probe through the phone,
every 10 min for 2 h after waking (morning surge). Sleep estimates skip AWAKE stages and the 5 min after one (`SettledSleepStageGate`; stage events
arrive batched, up to 5 min late) and are always made on the watch (`LocalAsleepBloodPressureMeasurer`, no radio), without the resting systolic floor
so the nocturnal dip shows; MCP `get_blood_pressure` gives each reading the stored `sleep_stage` it falls in. Generic Wear OS watches have no stages,
so no stage gate and no wake-time windows. A sleep measurement records a pulse strip (`NightPulseStripRecorder`: the shared `RrEcgCapturer`, so it never overlaps a
requested strip, 30 s of beats + raw wave, no screen) only when `AdaptiveNightStripPolicy` finds one due - a DEEP stage settled for 5 min, at most every 60 min,
or, out of turn, while the resting heart-rate trigger is in ALERT (`HeartRateAlertNightStrips` records on the transition, at most every 20 min) - and a 10 s
gyroscope probe confirms a still wrist; into `FileNightStripQueue` (`noBackupFilesDir/night_strips`, newest 120). `NightStripSender`, a
`FlushCompanion`, holds them while asleep and sends them on `/ecg/night-strip` with the flush after the session ends (the service flushes on `sessionChanges` -> false). The phone (`StoringEcgMessageReceiver.onNightStrip`) stores them in
`ecg_recording` with `kind` = NIGHT_PULSE (`EcgRecordingKind`, cardio.db v8; cloud payload `"kind"`, opaque to both servers), listed with the strips
as "in sleep (pulse wave)" (the tab's card keeps the last requested strip), and `NightPulseBloodPressureRecorder` stores the ridge model's estimate as
PULSE_WAVE_DIASTOLIC/SYSTOLIC (codes 48/49, history only, uploaded with the samples) once the model has >= 5 cuff pairs; before that only the strip is kept. Offline: the phone sends its calibration + current systolic offset on `/blood-pressure/calibration`
(`WatchBloodPressureCalibrationPublisher`, deduplicated); the watch caches it (`PhonePayloadStores.bloodPressureCalibration`) and, when a
measure request can't be sent, `CachedCalibrationOfflineEstimator` estimates with the mirrored `LocalBloodPressureEstimator` (keep in sync
with the phone's) from HR <= 5 min old and queues BLOOD_PRESSURE_DIASTOLIC + SYSTOLIC in the sample buffer for the next sync (history only, source WATCH). Both apps chart it (`dashboard/BloodPressureChart.kt`): watch page (the day), phone Metrics card and the
Blood Pressure tab (where the selected point gets Delete) (phone: the last 3 days, scrolled horizontally at 20 dp/h with the newest at the right, sticky value axis, day
separators; the slightly rounded curve and band pass through every reading, cuff and wrist estimate alike; every reading is a dot) - tap a point for its details; no text list anymore.
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

### TODO: watch battery

- Live sync (`LiveWatchSync`, `LiveWindowCadence`): decide how to make it cheaper. While the phone app is on screen the watch runs PPG windows back to back, the Measure API, a wake lock and a held socket, on any tab but System.
- Night monitor batching (implemented 2026-11-06, unverified on the device): check the `Night FIFO:` / `batched (FIFO)` / "delivered nothing" lines of `NightBreathingSource` after a night - if the FIFO is too small or the vendor sensors stop while suspended, it falls back to the whole-night wake lock.

### Watch battery on the phone

`SensorSyncSender` runs its `FlushCompanion`s at the end of every flush (radio already up, no own wakeups):
`WatchBatteryReporter` (watch `battery/`, sticky `ACTION_BATTERY_CHANGED`) sends `/status/battery`
(`{"percent", "charging", "at"}`). Phone `watchbattery/`: `StoringWatchBatteryReceiver` keeps it in the
process-wide `SharedPreferencesWatchBatteryStore` and re-renders the advisor widget; the title bar's
`WatchBatteryIndicator` observes it. Up to one sync interval (30 min) late; greyed out after 2 h.

Immediate sync on phone requests: the Blood Pressure tab's "Measure" first calls `WatchSyncRequester` (phone
`datalayer/WatchSync.kt`) - `/command/sync_now` (`{"request_id"}`) -> watch `ImmediateSyncResponder` (via the
service intent `ACTION_SYNC_NOW`) runs `SensorSyncSender.flushNow()` (backlog + battery) and answers
`/command/sync_now/result` (`{"request_id", "delivered"}`, through `WatchReplies`); the link is ordered, so the pushes are
stored before the answer. Waits up to 45 s, then estimates from what is stored. A snapshot (`SnapshotController`)
flushes the buffer before sending its result, so the phone's history is fresh when the snapshot lands.

Live sync while the phone app is on screen: `LiveWatchSync` (phone `datalayer/`, bound to `MainActivity`'s STARTED state)
sends `/sync/live` (`{"active": true}`, renewed every 30 s; `false` on stop). Watch `LiveSyncLease` (process-wide, lapses
after 75 s without a renewal, so a killed phone process can't leave it on) switches three things: `SensorSyncSender`
flushes every 5 s on a `delay` under a wake lock (inexact alarms can't pace seconds) instead of the 30 min alarm, with the
battery companion once a minute; `LiveWindowCadence` runs the measurement windows back to back (each still 3 min, SpO2
needs ~2 min to converge, so those readings arrive every 3 min with the PPG lit throughout); `BoostedHeartRateSampleSource`
streams the Measure API at one sample per 5 s. Each direction keeps one socket open meanwhile (`HeldRfcommLink` as
`LinkHold`; `TransportProtocol`: frames repeat on a socket until the sender closes it, receivers loop): the watch's for the
service's transport, the phone's process-wide in `TransportFactory`; resumable uploads always use their own socket. APK
transfers come first: live sync stops (lease ended, sockets closed) while the System tab is open (`setPaused`) and during
any bulk send (`LinkArbiter.bulkActive`), so an update outlasting the tab or started by the deploy script isn't disturbed.

### ECG card (watch rhythm strip)

The watch has no ECG electrodes; the "ECG" on the Blood Pressure / ECG tab is a **rhythm strip from `psp_rri`**
(vendor PPG beat-to-beat intervals). Phone `ecg/`: `TransportEcgRequester` sends `/command/ecg_capture`
(`{"request_id"}`) -> watch `ecg/EcgController` (service intent `ACTION_CAPTURE_ECG`) runs `RrEcgCapturer` (worn gate,
one `psp_rri` registration, up to 20 s lock-on, then 30 s), sends the new beats on `/ecg/progress` every 2 s and the
strip on `/ecg/result` (`{"t" offset = running RR sum, "rr", "ok"}` per beat). `StoringEcgMessageReceiver` saves
COMPLETED strips in `cardio.db` table `ecg_recording` (Room v2, AutoMigration 1->2) and updates the process-wide
`ProcessEcgSessions`, which `EcgStore` follows. The watch also records the raw PPG
pulse channel (`VendorPpgWaveSource`, `pah815x_ppg` values[3], ~32 Hz) as `wave` (`{"t": [offset ms], "v": [raw]}`,
offset by the wall clock from the strip start) in progress and result; `cardio.db` v3 adds `ecg_recording.wave`.
While recording the watch shows `EcgCaptureActivity` (screen on, heart beating at the measured rate, progress ring),
opened directly or via a full-screen notification (`ActivityEcgCaptureScreen`), fed by the process-wide
`EcgCaptureState`. `EcgCard` draws ECG paper (25 mm/s, 10 mm/mV, 6 s rows) as `SyntheticEcgTrace` (illustrative P-QRS-T, `EcgWaveform`) at the measured beats; `EcgStrip.of(beats, pulse)` shifts the beat times (RR sum anchored at a batched arrival) onto the analyzed pulse peaks minus 300 ms (R peak -> wrist pulse peak), so each complex sits where the heart beat. The raw PPG wave itself is not drawn. While recording the paper stays blank under a beating heart + scan sweep; the cardiogram appears once the strip is finished and analyzed. Tap = stored strips. Not a medical ECG: the user plans to compare readings against a clinical 12-lead machine.
`EcgRhythmAnalyzer`: premature beats (< 0.8x local median + compensatory > 1.1x, or isolated between two
0.85-1.15x intervals - two such beats once faked an AF call), pauses (> 1.6x), AF
screen (nRMSSD > 0.1, 16-bin entropy > 0.7, turning points 0.54-0.77), brady/tachy 50/100, sinus arrhythmia, RMSSD vagal tone;
< 15 clean beats or < 60 % clean = inconclusive. `PulseAwareCardiogramAnalyzer` (a `CardiogramAnalyzer` over the
rhythm analyzer + `RawPulseWaveAnalyzer`) adds from the wave: pulses (foot/peak/half-width per segment, 31.25 ms grid),
perfusion index (median pulse height / mean raw, %), breathing (baseline, height and interval modulations, strongest
0.1-0.5 Hz peak, edge peaks rejected), rise time, b/a of the foot-aligned averaged pulse's 2nd derivative (shown only
within -1.2..-0.2), wrist movement (detrended wave > 1.5x the median pulse height, +-0.5 s) marks
its beats not clean before the rhythm analysis, premature beats checked by a weak (< 0.75x) or missing pulse (`BeatWaveAlignment`), AF by pulse
height CV > 0.35. Pulse-wave blood pressure: after each live cuff reading `CuffReadingRecorder` (`CuffReadingFollowUp`) sends `/command/ecg_prompt` - the watch buzzes and `EcgCaptureActivity` asks to sit down and press OK (`EcgCapturePhase.AWAITING_CONFIRMATION`); Later, a swipe or 3 min of silence reply status DECLINED, OK records the strip; `PulseBloodPressureCalibrator` (an `EcgRecordingStoredListener`) pairs a stored strip with a
cuff reading 10 min before .. 2 min after its start into `cardio.db` v4 `pulse_bp_pair` (HR, rise time, half-width,
perfusion); `RidgePulseBloodPressureEstimator` fits SYS/DIA (standardized ridge, lambda 1, newest 60 pairs, >= 5
needed, leave-one-out RMS shown, +-15 mmHg of the trained range) - display only, not saved as a reading. Night:
synced setting `night_pulse_wave` (default off, phone Settings > Watch features) adds `PulseAmplitudeDropDetector` to
the night monitor (PPG at full rate all night; 2 s p90-p10 envelope >= 30 % below the 2-min median for >= 10 s ->
PULSE_AMPLITUDE_DROP, code 46, history only; MCP `get_sleep_breathing` counts them). Raw PPG (`pah815x_ppg`, 65572) for a real pulse wave: the
`DEBUG_PROBE_WATCH_RAW_PPG` broadcast -> watch `RawPpgProbe` records it with `psp_rri` for 40 s -> phone
`files/watch_probes/raw-ppg-<ms>.json`; ~32 Hz, values[3] is the pulsatile channel (inverted), values[2] constant. Retention in Settings (`EcgRetention` 3/9/18/30 days, default 30),
purged on change and by the daily `SensorHistoryPurgeWorker` (`EcgModule`).

### phone-app cardio: geomagnetic alerts, medication log, doctor's report

Geomagnetic alerts: after each refresh `SpaceWeatherRefresher` runs `GeomagneticAlerter` - `GeomagneticAlertPolicy` takes
the peak Kp of the 3-hour slots running or starting within 48 h and alerts from `GeomagneticLevel.ACTIVE` (Kp 4; G1+ is
a storm); `SharedPreferencesGeomagneticAlertLedger` re-announces only a higher level or after 24 h.
`SystemGeomagneticAlertNotifier` posts on `channel_space_weather_alerts`; the tap opens `MainActivity.cardioAdviceIntent`
(Blood Pressure tab + `CardioAdviceBottomSheet`, generic advice from `CardioAdvisor`).

`medication/`: only the fact and time of an intake (`MedicationDose`) - no drug names, doses or regimen in the app;
the therapy is decided with the physician and managed outside (planned: an external model). The Blood Pressure tab's button logs an intake into `cardio.db` - the app's
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
`MainActivity.advisorIntent`; draws the tab's two-ring gauge as a bitmap, `ReadinessGaugeBitmap`, same arc and palette) and MCP `get_cardio_readiness`, all over one `CardioReadinessSource`.
`ReadinessSnapshotReader` gathers rollup signals (15-min HR else resting HR vs the 14-day mean of the same local hour,
2 h RMSSD, last BP within 24 h + its ABPM dipping %, SLEEP_SCORE, 24 h steps, 48 h TRIMP, last intake, Kp slot).
`CardioReadinessEngine` = 100 + `ReadinessRule`s (diastolic >= 90/95 -25/-40, HR > norm +10/+20 -15/-30, RMSSD < 25
-15, > 15k steps after sleep < 70 -15, fatigue index >= 60/80 % -15/-30, Kp >= 5 -10, good sleep + 10-20 % dip +10; info rules for TRIMP/medication) ->
`ReadinessState` bands 80/55/35 with `AdviceSelector` (no-stimulants card when tachycardia dominates).
`ReadinessForecaster` re-scores the next 12 h with the same engine: `MedicationEffectCurve` (ramp 2 h, plateau to 8 h,
50 % at 24 h) lowers diastolic/HR excess by up to 8, 06-09 adds a 5 mmHg morning surge, HR norm and Kp follow the hour,
the fatigue index rises 1 %/h through 18-22 and reverts to the current value after that (sleep isn't modelled);
Optimal hours 07-20 are walk windows. Add rules by registering them in `AppContainer.cardioReadiness`. Heuristic only.
Post-stress window (`advisor/AutonomicBalance.kt`, `HistoryAutonomicBalanceSource`): the end of the last awake-rest HIGH deviation episode (`RecentDeviationEpisodes.lastEnded`) or of a sharp resting HR fall (`HeartRateDropDetector`: 5-min bucket >= resting +20 bpm back to <= +8, no bucket > 200 steps between) opens a 90 min window, stretched up to x2 by sleep score < 70 and fatigue > 60 %. `AutonomicBalanceCalculator`: 100 - strain, strain = 60 % vagal withdrawal (1 h RMSSD vs the hour norm, full at 60 %) + 40 % HR excess (15 min HR vs resting HR, full at +20 bpm); `AutonomicTone` POST_STRESS < 40 / RECOVERING < 70 inside a window, STRAINED < 40 outside. Display only, not a readiness rule. Advisor gauge: third (inner) ring, pulsing amber while post-stress, + `AutonomicToneCard` (breathing pause, pause before conversations/load); `/insights/readiness` optional keys `autonomic_readiness`, `autonomic_level`, `autonomic_label`, `post_stress` -> watch `CardioReadinessPage` third ring + "Take a breathing pause" in place of the advice. The widget (`ReadinessGaugeBitmap`) and the readings tile (`ReadinessGaugeTileElement`) draw the same three rings, without the pulse.
Watch tile "Cardio readiness" (`tile/CardioReadinessTileService`): score on top, BP and pulse below (a triangle of bare
numbers), coloured by `VitalLevel` (phone-classified: readiness band, ESC BP bands, resting-pulse bands), grey after
3 h. The phone sends `CardioReadinessPayload` on `/insights/readiness` after each sensor push and BP log change
(`DedupingPayloadPublisher`, the shared base of the watch-state publishers); the watch store requests a tile update.
The readings tile (`TicWatchTileService`, HR + ambient temperature) has the dashboard's two-ring gauge between its readings (`ReadinessGaugeTileElement`, native arc lines; tiles can't scroll, so it all fits one screen).
The watch dashboard's first page (`CardioReadinessPage`) shows the same payload like the Advisor: the two-ring gauge, state and advice labels (`state_label`, `advice`, optional keys resolved with `appTextResolver`), pressure and pulse. The `TicWatchFaceRenderer` face draws the score between pulse and temperature at their size (glyph coloured by level, grey when stale).

### phone-app advanced sleep analytics and medication phase

`sleep/SleepAdvancedAnalytics.kt`: pure calculators composed by `SleepAdvancedAnalytics`, fed by
`SleepAdvancedInsightsReader` (stages from `sleep_stage`, per-epoch SNORING/HR/RMSSD + evening SKIN_TEMPERATURE from raw
rows, night/day RMSSD means from 5-min rollups). `SleepContinuityCalculator` (WASO, awakenings, fragmentation index =
1 - longest bout / total sleep; stage gaps > 5 min end a bout without counting as awake), `SnoreCardiacArousalAnalyzer`
(snoring epoch mean HR > 1.1 x median of quiet-epoch HR = autonomic stress snoring, else benign), `VagalRecoveryCalculator`
(night RMSSD / previous day 08-21 RMSSD: >= 1.5 / 1.1 / 1.0 bands), `EveningVasodilationDetector` (>= 1.2 °C skin rise in the
2 h before onset vs the 1 h minimum before it), `ApneaRiskClassifier` (ODI bands, >= 3 snore-cardiac spikes raise LOW to MILD).
Used by MCP `get_sleep_advanced_insights` (`date` = wake-up day), the report's "Sleep and night recovery" section and the
Advisor's recovery badge (`VagalRecoverySource`). `medication/MedicationProtectionTracker`: phase of the anonymous
morning intake (0-1 h onset, 1-5 peak, 5-18 plateau, 18-24 waning, > 24 overdue, none in 7 days = not logged);
`MedicationProtectionRule` (+5 peak, -5 waning, -15 overdue) replaced the info-only rule, so the forecast follows the phase.

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
(source WATCH) within 5 min of the reading and newer than the current calibration; when none is stored yet it pulls the
watch buffer (`WatchSyncRequester`) and retries at once, then at +10/20/30 min. A watch "Measure" (`measureAndSave`)
within 5 min of a cuff reading the calibration doesn't include yet calibrates from it first, paired with the live HR. A cuff calibration (`calibrateFromCuff`, also via `calibrateFromRecentCuff`) is refused as `NotAtRest` by `WristCalibrationRestCheck` when the pulse is > resting HR + 12 bpm or the 3-min least-squares HR slope is > 6 bpm/min (manual calibration isn't checked). The estimator caps the systolic fall at 15 mmHg below the calibration while the pulse is at or above the lower edge of the personal resting corridors (`RestingHeartRateFloor`, min of awake/sleep low); below it, and in the watch's sleep estimates, the ±35 band applies. Battery/serial in `SharedPreferencesCuffStatusStore`, shown on the Blood Pressure tab. Schema v6 adds
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
- **One-shot dev loop: `deploy.bat` / `deploy.ps1` at the repo root.** Builds both apps
  (the watch as `assembleRelease`: R8 + resource shrinking, ~4 MB instead of 43 MB, not obfuscated, `proguard-rules.pro`,
  signed with the debug key so it installs over a debug build; anything reached by reflection needs a keep rule there),
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
  This returns immediately; the transfer itself (it took 60-120+s, sometimes several minutes,
  for a ~26MB debug APK over Bluetooth Classic; `deploy.ps1` now sends the ~4 MB release APK) runs in the background under the
  foreground-service notification, with no progress logging until it finishes — a long
  silent wait is expected, not a hang. Payloads > 256 KB go over the resumable RFCOMM
  frame (`TransportProtocol`: SHA-256 handshake + resume offset, 64 KB chunks each ACKed,
  hash verified at the end; watch keeps `noBackupFilesDir/incoming_transfers/<sha>.part`),
  so a dropped link resumes instead of resending the APK; a watch-app that predates it
  replies a plain ACK and the phone falls back to the one-shot frame. Those payloads try Wi-Fi first
  (`LargePayloadRoutingTransport` -> `WifiLanTransport`, skipped when the phone isn't on Wi-Fi): the phone asks over
  RFCOMM (`/transport/wifi-offer/v1`), the watch's `WifiLinkServer` requests the Wi-Fi network (up to 20 s) and answers
  host, port and a session key; the same resumable frame then runs over TCP inside AES-GCM `SealedLinkStreams`, and
  any failure falls through to RFCOMM, which resumes from the same `.part` file. Both must be on one LAN; phone->watch
  only; logged as `WifiLanTransport: Sent ... over Wi-Fi in N ms`. Every phone send
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

### Phone link without a companion app

Since 2026-10-27 the phone has no Mobvoi/Wear OS companion, so GMS Wearable never starts on it (`MISSING_COMPANION_APP`) and
the Data Layer fallback is dead; only this app's RFCOMM traffic pages the phone after the Bluetooth link times out. The
watch's "phone crossed out" icon does NOT follow that link: it is Wear OS's own connectivity status (phone via the GMS
node connection, else Wi-Fi). Without a companion the phone path never counts, so the icon is crossed out whenever the
watch has no Wi-Fi, even while the ACL is up and RFCOMM sync works; reconnecting the watch's Wi-Fi clears it (seen
2026-11-04; an app request for the Wi-Fi network, e.g. `WifiLinkServer`, can clear it too). Don't use the icon as a
link-health signal - check phone `dumpsys bluetooth_manager` ACL state
and `BluetoothLinkServer` lines instead. So nothing on the watch side may block or defer it: every RFCOMM
connect/frame runs under `SocketDeadline.withDeadline` (30 s connect, 90 s frame + ACK, inside the flush's 2 min wake lock),
and the sync flush is an allow-while-idle alarm.

### Internet via phone: not possible on this watch

Tried and removed 2026-11-04: a watch `VpnService` tunnelling over RFCOMM to a phone relay. Wear OS on this watch (API 33)
has no `vpn_management` service, so `VpnService.prepare()` never succeeds (the consent intent points at a non-exported
`frameworkpackagestubs` stub; even the `ACTIVATE_VPN` appop doesn't help), and Bluetooth has no PAN profile. Without the
companion app the watch gets internet only over Wi-Fi (e.g. the phone's hotspot).
