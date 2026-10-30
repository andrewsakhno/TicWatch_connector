package org.sand.ticwatch.watch.datalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/** The phone's [DataLayerPaths.MESSAGE_SYNC_NOW] command, handed to the sensor service that owns the sample buffer. */
fun interface SyncNowRequester {
    fun request(requestId: String?)
}

/** `/command/sync_now` request and result, mirrored by phone-app's `SyncNowPayloads`. */
object SyncNowPayloads {
    const val REQUEST_ID = "request_id"
    const val DELIVERED = "delivered"

    fun decodeRequestId(payload: ByteArray): String? =
        runCatching { JSONObject(String(payload, Charsets.UTF_8)).optString(REQUEST_ID) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun encodeResult(requestId: String?, delivered: Boolean): ByteArray = JSONObject()
        .put(DELIVERED, delivered)
        .apply { requestId?.let { put(REQUEST_ID, it) } }
        .toString()
        .toByteArray(Charsets.UTF_8)
}

/**
 * Flushes the buffered samples (and the flush companions' status) at once, then answers on
 * [DataLayerPaths.MESSAGE_SYNC_NOW_RESULT]. The link delivers in order, so the phone has stored every pushed batch
 * by the time the answer arrives.
 */
class ImmediateSyncResponder(
    private val flusher: suspend () -> Boolean,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
) : SyncNowRequester {
    override fun request(requestId: String?) {
        scope.launch {
            val delivered = runCatching { flusher() }
                .onFailure { Log.w(TAG, "Immediate sync failed", it) }
                .getOrDefault(false)
            transport.sendMessage(DataLayerPaths.MESSAGE_SYNC_NOW_RESULT, SyncNowPayloads.encodeResult(requestId, delivered))
                .onFailure { Log.w(TAG, "Sync-now result not delivered", it) }
        }
    }

    private companion object {
        const val TAG = "ImmediateSyncResponder"
    }
}
