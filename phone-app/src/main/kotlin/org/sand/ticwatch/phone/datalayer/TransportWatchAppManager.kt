package org.sand.ticwatch.phone.datalayer

import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.tasks.await
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendFile
import org.sand.ticwatch.phone.transport.sendMessage
import java.io.File

/**
 * [WatchAppManager] backed by [DeviceLinkTransport] (Wear Data Layer, falling back to
 * direct Bluetooth) instead of talking to `MessageClient`/`ChannelClient` directly.
 * Watch discovery for the paired-watches list still goes through [nodeClient], since
 * that's purely informational for the UI; sends always target the app's one bonded
 * watch, so `watchNodeId` isn't needed to route them.
 */
class TransportWatchAppManager(
    private val nodeClient: NodeClient,
    private val transport: DeviceLinkTransport,
) : WatchAppManager {

    override suspend fun listPairedWatches(): List<WatchNode> =
        nodeClient.connectedNodes.await().map { node ->
            WatchNode(id = node.id, displayName = node.displayName, isNearby = node.isNearby)
        }

    override suspend fun installApp(watchNodeId: String, apk: File, packageName: String): Result<Unit> = runCatching {
        transport.sendMessage(
            DataLayerPaths.MESSAGE_INSTALL_APP_REQUEST,
            packageName.toByteArray(Charsets.UTF_8),
        ).getOrThrow()
        transport.sendFile(DataLayerPaths.CHANNEL_APK_TRANSFER, apk).getOrThrow()
    }

    override suspend fun uninstallApp(watchNodeId: String, packageName: String): Result<Unit> =
        transport.sendMessage(DataLayerPaths.MESSAGE_UNINSTALL_APP_REQUEST, packageName.toByteArray(Charsets.UTF_8))
}
