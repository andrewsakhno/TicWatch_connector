package org.sand.ticwatch.phone.transport

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import android.util.Log
import kotlinx.coroutines.tasks.await
import java.io.InputStream
import java.io.OutputStream

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
                    source.open().use { copyPausable(it, output, currentLinkPreemption()) }
                }
            } finally {
                channelClient.close(channel).await()
            }
        }
    }

    /**
     * Channels can't resume, so a pause keeps this one open and just stops writing until
     * the interactive sends are done.
     */
    private suspend fun copyPausable(input: InputStream, output: OutputStream, preemption: LinkPreemption?) {
        val buffer = ByteArray(COPY_CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (preemption?.isRequested == true) {
                output.flush()
                Log.i(TAG, "Pausing channel transfer for interactive sends")
                preemption.yieldLink()
            }
        }
    }

    companion object {
        private const val TAG = "DataLayerTransport"
        private const val COPY_CHUNK_SIZE = 64 * 1024
        private const val MESSAGE_SIZE_THRESHOLD = 100 * 1024L
    }
}
