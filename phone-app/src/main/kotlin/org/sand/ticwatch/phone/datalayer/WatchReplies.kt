package org.sand.ticwatch.phone.datalayer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Receiving side: the message router hands over replies carrying a request id. */
fun interface WatchReplySink {
    fun deliver(requestId: String, payload: String)
}

/** Requesting side: waits for the watch's reply to one request id. */
interface WatchReplyAwaiter {
    /** Returns the reply, or null when none arrived within [timeout]. */
    suspend fun await(requestId: String, timeout: Duration, send: suspend () -> Result<Unit>): Result<String?>
}

/**
 * Process-wide, because replies arrive through whichever listener service the transport woke
 * (each with its own `AppContainer`) while the caller waiting for them lives in another.
 */
object WatchReplies : WatchReplySink, WatchReplyAwaiter {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    override fun deliver(requestId: String, payload: String) {
        pending.remove(requestId)?.complete(payload)
    }

    override suspend fun await(requestId: String, timeout: Duration, send: suspend () -> Result<Unit>): Result<String?> {
        val reply = CompletableDeferred<String>()
        pending[requestId] = reply
        return try {
            send().map { withTimeoutOrNull(timeout) { reply.await() } }
        } finally {
            pending.remove(requestId)
        }
    }
}
