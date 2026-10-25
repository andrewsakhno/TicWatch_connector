package org.sand.ticwatch.watch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.sand.ticwatch.watch.sensors.SensorCollectionService
import org.sand.ticwatch.watch.transport.BluetoothLinkServerService

/**
 * Restarts the long-running services after a watch reboot or an app update. Both kill
 * them and neither restores a START_STICKY service, so without this collection and the
 * phone link stayed down until someone opened the app on the watch again.
 *
 * Each service is started only once its runtime permission is granted: starting a typed
 * foreground service without it throws SecurityException (the first-run grant happens
 * in [MainActivity]).
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in TRIGGER_ACTIONS) return

        if (isGranted(context, Manifest.permission.BODY_SENSORS)) {
            SensorCollectionService.start(context)
        }
        if (isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            ContextCompat.startForegroundService(context, Intent(context, BluetoothLinkServerService::class.java))
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val TRIGGER_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
