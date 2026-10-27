package org.sand.ticwatch.phone.datalayer

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.sync.SyncStatusRepository

/**
 * Wear Data Layer entry point: receives install/uninstall acknowledgements and sensor
 * data batches pushed up from watch-app, and forwards each into [PhoneMessageRouter] -
 * the same router `BluetoothLinkServerService` uses for the direct-Bluetooth fallback
 * path. Registered in the manifest so the system starts it on demand even if
 * MainActivity isn't running.
 */
class PhoneDataLayerListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContainer by lazy { AppContainer(applicationContext) }
    private val router by lazy {
        PhoneMessageRouter(
            appContainer.sensorDataSyncWorker,
            appContainer.workoutRouteRecorder,
            appContainer.exerciseRouteLog,
            SyncStatusRepository,
            WatchReplies,
            appContainer.watchStatePublishers,
            appContainer.bloodPressureRequestHandler,
        )
    }

    override fun onMessageReceived(event: MessageEvent) {
        // The Data Layer has no reply channel for a NACK, so the handled flag is unused here.
        serviceScope.launch { router.handleMessage(event.path, event.data) }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
