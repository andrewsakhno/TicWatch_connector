package org.sand.ticwatch.phone.datalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.insights.HealthScoresPayload
import org.sand.ticwatch.phone.insights.HealthScoresSource
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.util.concurrent.atomic.AtomicReference

/** Tells the watch about fresh derived scores once new samples were stored. */
fun interface HealthScoresPublisher : WatchStatePublisher

/**
 * Recomputes [HealthScoresSource] on [scope] (so the incoming push's transport session isn't held up) and
 * sends it on [DataLayerPaths.MESSAGE_HEALTH_SCORES] only when it differs from the last payload sent.
 */
class WatchHealthScoresPublisher(
    private val source: HealthScoresSource,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
) : HealthScoresPublisher {

    private val lastSent = AtomicReference<String?>()

    override fun publish() {
        scope.launch {
            val payload = runCatching { HealthScoresPayload.encode(source.load()) }
                .onFailure { Log.w(TAG, "Computing health scores failed", it) }
                .getOrNull() ?: return@launch
            if (payload == lastSent.get()) return@launch
            transport.sendMessage(DataLayerPaths.MESSAGE_HEALTH_SCORES, payload.toByteArray(Charsets.UTF_8))
                .onSuccess { lastSent.set(payload) }
                .onFailure { Log.w(TAG, "Sending health scores to the watch failed", it) }
        }
    }

    private companion object {
        const val TAG = "HealthScoresPublisher"
    }
}
