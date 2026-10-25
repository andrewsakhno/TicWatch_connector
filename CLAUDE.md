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
