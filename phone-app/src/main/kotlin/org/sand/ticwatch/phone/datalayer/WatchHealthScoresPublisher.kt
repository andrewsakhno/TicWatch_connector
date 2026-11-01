package org.sand.ticwatch.phone.datalayer

import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.insights.HealthScoresPayload
import org.sand.ticwatch.phone.insights.HealthScoresSource
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.transport.DeviceLinkTransport

/** Tells the watch about fresh derived scores once new samples were stored. */
fun interface HealthScoresPublisher : WatchStatePublisher

/** Recomputes [HealthScoresSource] and sends it on [DataLayerPaths.MESSAGE_HEALTH_SCORES] when it changed. */
class WatchHealthScoresPublisher(
    source: HealthScoresSource,
    transport: DeviceLinkTransport,
    scope: CoroutineScope,
    texts: TextResolver,
) : HealthScoresPublisher {

    private val delegate = DedupingPayloadPublisher(DataLayerPaths.MESSAGE_HEALTH_SCORES, transport, scope, TAG) {
        HealthScoresPayload.encode(source.load(), texts)
    }

    override fun publish() = delegate.publish()

    override suspend fun publishAndWait() = delegate.publishAndWait()

    private companion object {
        const val TAG = "HealthScoresPublisher"
    }
}
