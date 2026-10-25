package org.sand.ticwatch.phone.datalayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.transport.TransportFactory
import org.sand.ticwatch.phone.transport.sendMessage

/**
 * adb-triggerable entry point into the watch debug bridge for [ACTION_PING_WATCH_SENSORS]:
 * forwards a sensor-snapshot request to the watch through the standard fallback transport
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
        when (intent.action) {
            ACTION_PING_WATCH_SENSORS -> {
                CoroutineScope(Dispatchers.IO).launch {
                    TransportFactory.create(appContext)
                        .sendMessage(DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_REQUEST, ByteArray(0))
                        .onSuccess { Log.i(TAG, "Sent sensor-snapshot request to the watch") }
                        .onFailure { error -> Log.w(TAG, "Failed to send sensor-snapshot request", error) }
                }
                pendingResult.finish()
            }

            else -> pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_PING_WATCH_SENSORS = "org.sand.ticwatch.phone.action.DEBUG_PING_WATCH_SENSORS"
        private const val TAG = "DebugBridgeReceiver"
    }
}
