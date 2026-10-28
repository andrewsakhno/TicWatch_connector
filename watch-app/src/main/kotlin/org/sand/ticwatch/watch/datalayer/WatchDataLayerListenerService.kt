package org.sand.ticwatch.watch.datalayer

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.sand.ticwatch.watch.install.PackageInstallerApkInstaller
import org.sand.ticwatch.watch.sensors.HealthServicesSensorCollector
import org.sand.ticwatch.watch.sensors.SensorCollectionService
import org.sand.ticwatch.watch.transport.TransportFactory

/**
 * Wear Data Layer entry point: receives install/uninstall requests, the APK byte
 * stream, and the debug-sensor-snapshot request, and forwards each into
 * [WatchMessageRouter] - the same router `BluetoothLinkServerService` uses for the
 * direct-Bluetooth fallback path.
 */
class WatchDataLayerListenerService : WearableListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val router: WatchMessageRouter by lazy {
        WatchMessageRouter(
            context = applicationContext,
            apkInstaller = PackageInstallerApkInstaller(applicationContext),
            sensorCollector = HealthServicesSensorCollector(applicationContext),
            debugProbes = defaultDebugProbeRoutes(applicationContext),
            replyTransport = TransportFactory.create(applicationContext),
            phonePayloads = WatchMessageRouter.phonePayloadSinks(applicationContext, scope),
            snapshotRequester = SensorCollectionService.snapshotRequester(applicationContext),
            scope = scope,
        )
    }

    override fun onMessageReceived(event: MessageEvent) {
        router.handleMessage(event.path, event.data)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != DataLayerPaths.CHANNEL_APK_TRANSFER) return

        scope.launch {
            val channelClient = Wearable.getChannelClient(applicationContext)
            channelClient.getInputStream(channel).await().use { input -> router.handleIncomingApk(input) }
            channelClient.close(channel).await()
        }
    }
}
