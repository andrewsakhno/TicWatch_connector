package org.sand.ticwatch.phone.datalayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.bpm.BiolandProtocol
import org.sand.ticwatch.phone.bpm.BpmGattProbe
import org.sand.ticwatch.phone.bpm.BpmProbeCommands
import org.sand.ticwatch.phone.health.LegacyRecordCleanup
import org.sand.ticwatch.phone.history.BodyTemperatureBackfillWorker
import org.sand.ticwatch.phone.history.SensorHistoryPurgeWorker
import org.sand.ticwatch.phone.history.SleepNightReplaceWorker
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherRefreshWorker
import org.sand.ticwatch.phone.transport.TransportFactory
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.LocalDateTime
import java.util.UUID

/**
 * adb-triggerable entry point into the watch debug bridge for [ACTION_PING_WATCH_SENSORS]
 * and [ACTION_PROBE_WATCH_CAPABILITIES]: forwards the matching request to the watch through the standard fallback transport
 * (Wear Data Layer, falling back to direct Bluetooth). Works whenever phone and watch are
 * bonded, regardless of whether the watch is reachable by adb - the response comes back to
 * [PhoneDataLayerListenerService]/`BluetoothLinkServerService` and lands in this app's own
 * logcat.
 *
 * Trigger with:
 *   adb shell am broadcast -a org.sand.ticwatch.phone.action.DEBUG_PING_WATCH_SENSORS
 *
 * Installing a watch-app build ([WatchAppInstallService]) is NOT done through this
 * receiver: Android 12+ blocks an app from calling `startForegroundService()` on itself
 * from a background-executing `BroadcastReceiver` (`BFGS denied`), which a bare
 * goAsync()-launched coroutine can't work around either - it just gets frozen by the
 * cached-app freezer mid-transfer once the receiver returns, since nothing else is
 * keeping the process alive. adb's shell UID isn't subject to that restriction, so start
 * the service directly instead:
 *   adb push watch-app-debug.apk /sdcard/Android/data/org.sand.ticwatch.phone/files/watch-app-debug.apk
 *   adb shell am start-foreground-service -n org.sand.ticwatch.phone/.datalayer.WatchAppInstallService \
 *       --es apkPath /storage/emulated/0/Android/data/org.sand.ticwatch.phone/files/watch-app-debug.apk
 */
class DebugBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        // pendingResult.finish() is called right after launching the work below, not
        // after it completes: goAsync() only extends the receiver's execution window by
        // a few seconds, not by however long sending a message can take under retry.
        REQUEST_PATHS[intent.action]?.let { path ->
            CoroutineScope(Dispatchers.IO).launch {
                TransportFactory.create(appContext)
                    .sendMessage(path, ByteArray(0))
                    .onSuccess { Log.i(TAG, "Sent $path to the watch") }
                    .onFailure { error -> Log.w(TAG, "Failed to send $path", error) }
            }
        }
        if (intent.action == ACTION_CAPTURE_WATCH_SCREEN) {
            val request = JSONObject()
            if (intent.hasExtra(EXTRA_PAGE)) request.put(EXTRA_PAGE, intent.getIntExtra(EXTRA_PAGE, 0))
            CoroutineScope(Dispatchers.IO).launch {
                TransportFactory.create(appContext)
                    .sendMessage(DataLayerPaths.MESSAGE_DEBUG_SCREENSHOT_REQUEST, request.toString().toByteArray(Charsets.UTF_8))
                    .onFailure { error -> Log.w(TAG, "Failed to request a watch screenshot", error) }
            }
        }
        LEGACY_CLEANUPS[intent.action]?.let { cleanupOf ->
            CoroutineScope(Dispatchers.IO).launch { cleanupOf(AppContainer(appContext)).run() }
        }
        if (intent.action == ACTION_RUN_HISTORY_MAINTENANCE) {
            SensorHistoryPurgeWorker.runNow(appContext)
        }
        if (intent.action == ACTION_REFRESH_SPACE_WEATHER) {
            SpaceWeatherRefreshWorker.runNow(appContext)
        }
        if (intent.action == ACTION_BACKFILL_BODY_TEMPERATURE) {
            BodyTemperatureBackfillWorker.enqueue(appContext, force = true)
        }
        if (intent.action == ACTION_REPLACE_SLEEP_NIGHT) {
            val payloadPath = intent.getStringExtra(EXTRA_PAYLOAD_PATH)
            if (payloadPath == null) {
                Log.w(TAG, "$ACTION_REPLACE_SLEEP_NIGHT needs --es $EXTRA_PAYLOAD_PATH")
            } else {
                SleepNightReplaceWorker.enqueue(
                    appContext, payloadPath, intent.getLongExtra(EXTRA_FROM, 0L), intent.getLongExtra(EXTRA_TO, 0L),
                )
            }
        }
        if (intent.action == ACTION_PROBE_BPM) {
            activeBpmProbe?.stop()
            val commands = BpmProbeCommands.parse(intent.getStringExtra(EXTRA_BPM_FRAMES).orEmpty(), LocalDateTime.now())
            val target = intent.getStringExtra(EXTRA_BPM_CHARACTERISTIC)
                ?.let { UUID.fromString("0000$it-0000-1000-8000-00805f9b34fb") } ?: BiolandProtocol.COMMAND_CHARACTERISTIC
            activeBpmProbe = BpmGattProbe(appContext, commands, target).also { it.start() }
        }
        pendingResult.finish()
    }

    companion object {
        const val ACTION_PING_WATCH_SENSORS = "org.sand.ticwatch.phone.action.DEBUG_PING_WATCH_SENSORS"
        const val ACTION_PROBE_WATCH_CAPABILITIES = "org.sand.ticwatch.phone.action.DEBUG_PROBE_WATCH_CAPABILITIES"
        const val ACTION_PROBE_WATCH_VENDOR_DATA = "org.sand.ticwatch.phone.action.DEBUG_PROBE_WATCH_VENDOR_DATA"
        const val ACTION_PULL_WATCH_SLEEP_LOG = "org.sand.ticwatch.phone.action.DEBUG_PULL_WATCH_SLEEP_LOG"

        /** Phone-local maintenance: deletes the per-sample resting-HR records older builds wrote. */
        const val ACTION_CLEANUP_LEGACY_RESTING_HR = "org.sand.ticwatch.phone.action.DEBUG_CLEANUP_LEGACY_RESTING_HR"

        /** Phone-local maintenance: deletes the active-only calories older builds wrote as total calories. */
        const val ACTION_CLEANUP_LEGACY_TOTAL_CALORIES = "org.sand.ticwatch.phone.action.DEBUG_CLEANUP_LEGACY_TOTAL_CALORIES"

        private val LEGACY_CLEANUPS: Map<String, (AppContainer) -> LegacyRecordCleanup> = mapOf(
            ACTION_CLEANUP_LEGACY_RESTING_HR to AppContainer::legacyRestingHeartRateCleanup,
            ACTION_CLEANUP_LEGACY_TOTAL_CALORIES to AppContainer::legacyTotalCaloriesCleanup,
        )

        /** Phone-local maintenance: runs the daily history purge + failed-delivery retry now. */
        /**
         * Asks the watch for a PNG of its app screen (the app must be open on the watch), optionally switching to
         * dashboard page `--ei page <n>` first; saved by [FileWatchScreenshotSink].
         */
        const val ACTION_CAPTURE_WATCH_SCREEN = "org.sand.ticwatch.phone.action.DEBUG_CAPTURE_WATCH_SCREEN"
        const val EXTRA_PAGE = "page"

        const val ACTION_RUN_HISTORY_MAINTENANCE = "org.sand.ticwatch.phone.action.DEBUG_RUN_HISTORY_MAINTENANCE"

        /** Phone-local: fetches NOAA space weather now and sends it to the watch. */
        const val ACTION_REFRESH_SPACE_WEATHER = "org.sand.ticwatch.phone.action.DEBUG_REFRESH_SPACE_WEATHER"

        /** Phone-local maintenance: re-runs the body-temperature backfill over the retained history. */
        const val ACTION_BACKFILL_BODY_TEMPERATURE = "org.sand.ticwatch.phone.action.DEBUG_BACKFILL_BODY_TEMPERATURE"

        /**
         * Phone-local repair: replaces this app's sleep in [EXTRA_FROM, EXTRA_TO) (epoch ms, `--el`) with the
         * sensor-push JSON at [EXTRA_PAYLOAD_PATH] (stage it in the app's files dir via `run-as`).
         */
        const val ACTION_REPLACE_SLEEP_NIGHT = "org.sand.ticwatch.phone.action.DEBUG_REPLACE_SLEEP_NIGHT"
        const val EXTRA_PAYLOAD_PATH = "payloadPath"
        const val EXTRA_FROM = "fromEpochMs"
        const val EXTRA_TO = "toEpochMs"

        /**
         * Phone-local: GATT-explores the bonded Bioland cuff for 10 min, logging under `BpmGattProbe`; optionally
         * writes [EXTRA_BPM_FRAMES] (see [BpmProbeCommands]) to the 16-bit characteristic [EXTRA_BPM_CHARACTERISTIC].
         */
        const val ACTION_PROBE_BPM = "org.sand.ticwatch.phone.action.DEBUG_PROBE_BPM"
        const val EXTRA_BPM_FRAMES = "frames"
        const val EXTRA_BPM_CHARACTERISTIC = "characteristic"

        /** Held process-wide so the autoConnect GATT client outlives the receiver. */
        private var activeBpmProbe: BpmGattProbe? = null

        private val REQUEST_PATHS = mapOf(
            ACTION_PING_WATCH_SENSORS to DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST,
            ACTION_PROBE_WATCH_CAPABILITIES to DataLayerPaths.MESSAGE_DEBUG_CAPABILITIES_REQUEST,
            ACTION_PROBE_WATCH_VENDOR_DATA to DataLayerPaths.MESSAGE_DEBUG_VENDOR_DATA_REQUEST,
            ACTION_PULL_WATCH_SLEEP_LOG to DataLayerPaths.MESSAGE_DEBUG_SLEEP_LOG_REQUEST,
        )
        private const val TAG = "DebugBridgeReceiver"
    }
}
