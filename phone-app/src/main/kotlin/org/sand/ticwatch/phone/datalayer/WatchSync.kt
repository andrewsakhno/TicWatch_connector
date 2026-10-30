package org.sand.ticwatch.phone.datalayer

import org.json.JSONObject
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.util.UUID
import kotlin.time.Duration

/** How an immediate sync ended. */
enum class WatchSyncOutcome {
    /** The watch's buffered samples are stored on the phone. */
    DELIVERED,

    /** The watch answered, but part of its backlog didn't get through. */
    PARTIAL,

    /** No answer in time, or the watch is unreachable: the stored history is what there is. */
    UNREACHABLE,
}

/** Pulls the watch's buffered samples now instead of waiting for its next sync window. */
fun interface WatchSyncRequester {
    suspend fun syncNow(timeout: Duration): WatchSyncOutcome
}

/** `/command/sync_now` request and result, mirrored by watch-app's `SyncNowPayloads`. */
object SyncNowPayloads {
    private const val REQUEST_ID = PhoneMessageRouter.REQUEST_ID
    private const val DELIVERED = "delivered"

    fun encodeRequest(requestId: String): ByteArray = JSONObject().put(REQUEST_ID, requestId).toString().toByteArray(Charsets.UTF_8)

    fun decodeDelivered(reply: String): Boolean = JSONObject(reply).optBoolean(DELIVERED, false)
}

/**
 * Sends [DataLayerPaths.MESSAGE_SYNC_NOW] and waits for [DataLayerPaths.MESSAGE_SYNC_NOW_RESULT]: the watch answers
 * after its flush, and the link delivers in order, so every pushed batch has been stored by then.
 */
class TransportWatchSyncRequester(
    private val transport: DeviceLinkTransport,
    private val replies: WatchReplyAwaiter,
) : WatchSyncRequester {
    override suspend fun syncNow(timeout: Duration): WatchSyncOutcome {
        val requestId = UUID.randomUUID().toString()
        val reply = replies.await(requestId, timeout) {
            transport.sendMessage(DataLayerPaths.MESSAGE_SYNC_NOW, SyncNowPayloads.encodeRequest(requestId))
        }.getOrNull() ?: return WatchSyncOutcome.UNREACHABLE
        val delivered = runCatching { SyncNowPayloads.decodeDelivered(reply) }.getOrDefault(false)
        return if (delivered) WatchSyncOutcome.DELIVERED else WatchSyncOutcome.PARTIAL
    }
}
