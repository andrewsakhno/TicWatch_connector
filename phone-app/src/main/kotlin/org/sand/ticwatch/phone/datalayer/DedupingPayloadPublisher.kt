package org.sand.ticwatch.phone.datalayer

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage

/**
 * Builds a payload with [encode] on [scope] (so the incoming push's transport session isn't held up) and sends it on
 * [path] only when it differs from the last payload sent; [encode] returning null or failing sends nothing.
 */
class DedupingPayloadPublisher(
    private val path: String,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val tag: String,
    private val encode: suspend () -> String?,
) : WatchStatePublisher {

    private val lastSent = AtomicReference<String?>()

    override fun publish() {
        scope.launch {
            val payload = runCatching { encode() }
                .onFailure { Log.w(tag, "Building the $path payload failed", it) }
                .getOrNull() ?: return@launch
            if (payload == lastSent.get()) return@launch
            transport.sendMessage(path, payload.toByteArray(Charsets.UTF_8))
                .onSuccess { lastSent.set(payload) }
                .onFailure { Log.w(tag, "Sending $path to the watch failed", it) }
        }
    }
}
