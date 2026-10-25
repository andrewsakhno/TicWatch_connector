package org.sand.ticwatch.phone.transport

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import kotlinx.coroutines.tasks.await

/**
 * [DeviceLinkTransport] backed by the Wear OS Data Layer. Small payloads go over
 * [MessageClient]; anything past [MESSAGE_SIZE_THRESHOLD] (APK transfers) streams
 * through a [ChannelClient] channel instead, since Data Layer messages are capped
 * around 100KB.
 */
class DataLayerTransport(
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient,
    private val channelClient: ChannelClient,
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> = runCatching {
        val watch = nodeClient.connectedNodes.await().firstOrNull() ?: error("No connected watch node")

        if (length <= MESSAGE_SIZE_THRESHOLD) {
            messageClient.sendMessage(watch.id, path, source.open().use { it.readBytes() }).await()
        } else {
            val channel = channelClient.openChannel(watch.id, path).await()
            try {
                channelClient.getOutputStream(channel).await().use { output ->
                    source.open().use { it.copyTo(output) }
                }
            } finally {
                channelClient.close(channel).await()
            }
        }
    }

    companion object {
        private const val MESSAGE_SIZE_THRESHOLD = 100 * 1024L
    }
}
