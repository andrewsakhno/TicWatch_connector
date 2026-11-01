package org.sand.ticwatch.phone.datalayer

import android.util.Log
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage

/** For a payload carrying its own timestamp: changes compare [withoutTimestamp], and unchanged content still goes out every [every] so the receiver sees it is fresh. */
class TimestampedPayloadRefresh(
    val withoutTimestamp: (String) -> String,
    val every: Duration,
    val clock: Clock,
)

/**
 * Builds a payload with [encode] on [scope] (so the incoming push's transport session isn't held up) and sends it on
 * [path] only when it differs from the last payload sent; [encode] returning null or failing sends nothing.
 */
class DedupingPayloadPublisher(
    private val path: String,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val tag: String,
    private val refresh: TimestampedPayloadRefresh? = null,
    private val encode: suspend () -> String?,
) : WatchStatePublisher {

    private class Sent(val content: String, val at: Instant?)

    private val lastSent = AtomicReference<Sent?>()

    override fun publish() {
        scope.launch { publishAndWait() }
    }

    override suspend fun publishAndWait() {
        val payload = runCatching { encode() }
            .onFailure { Log.w(tag, "Building the $path payload failed", it) }
            .getOrNull() ?: return
        val content = refresh?.withoutTimestamp?.invoke(payload) ?: payload
        val now = refresh?.clock?.instant()
        if (!isDue(content, now)) return
        transport.sendMessage(path, payload.toByteArray(Charsets.UTF_8))
            .onSuccess { lastSent.set(Sent(content, now)) }
            .onFailure { Log.w(tag, "Sending $path to the watch failed", it) }
    }

    private fun isDue(content: String, now: Instant?): Boolean {
        val last = lastSent.get() ?: return true
        if (last.content != content) return true
        val every = refresh?.every ?: return false
        return Duration.between(last.at, now) >= every
    }
}
