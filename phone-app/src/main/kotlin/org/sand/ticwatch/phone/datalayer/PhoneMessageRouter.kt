package org.sand.ticwatch.phone.datalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.sync.SyncDirection
import org.sand.ticwatch.phone.sync.SyncStatusReporter
import org.sand.ticwatch.phone.sync.describeSyncPath

/**
 * Business logic for requests arriving from the watch, independent of which transport
 * delivered them (Wear Data Layer or direct Bluetooth) - both
 * `PhoneDataLayerListenerService` and `BluetoothLinkServerService` forward into this
 * one place instead of duplicating the handling. Also the single seam for reporting
 * *incoming* transfers to [syncStatusReporter] - unlike sending, a Data Layer message
 * or a Bluetooth-link frame always arrives as one already-buffered byte array, so
 * there's no read-pace to instrument the way [org.sand.ticwatch.phone.transport.ProgressReportingDeviceLinkTransport]
 * does for sends; reporting here still surfaces size and direction on the Dashboard.
 */
class PhoneMessageRouter(
    private val sensorDataSyncWorker: SensorDataSyncWorker,
    private val syncStatusReporter: SyncStatusReporter,
    private val scope: CoroutineScope,
) {
    fun handleMessage(path: String, payload: ByteArray) {
        syncStatusReporter.begin(SyncDirection.FROM_WATCH, describeSyncPath(path, SyncDirection.FROM_WATCH), payload.size.toLong())
        syncStatusReporter.updateProgress(payload.size.toLong())

        when (path) {
            DataLayerPaths.MESSAGE_INSTALL_APP_RESULT,
            DataLayerPaths.MESSAGE_UNINSTALL_APP_RESULT -> {
                Log.i(TAG, "Watch app-management result on $path: ${String(payload, Charsets.UTF_8)}")
                syncStatusReporter.end()
            }

            DataLayerPaths.MESSAGE_DEBUG_SENSOR_SNAPSHOT_RESPONSE -> {
                Log.i(TAG, "Watch sensor snapshot: ${String(payload, Charsets.UTF_8)}")
                syncStatusReporter.end()
            }

            DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH ->
                scope.launch {
                    sensorDataSyncWorker.onSensorDataPush(payload)
                        .onSuccess { count -> Log.i(TAG, "Wrote $count sensor sample(s) to Health Connect") }
                        .onFailure { Log.w(TAG, "Failed to write sensor data to Health Connect", it) }
                    syncStatusReporter.end()
                }

            else -> {
                Log.d(TAG, "Unhandled message path: $path")
                syncStatusReporter.end()
            }
        }
    }

    companion object {
        private const val TAG = "PhoneMessageRouter"
    }
}
