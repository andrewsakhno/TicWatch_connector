package org.sand.ticwatch.phone.datalayer

import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherPayload
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.transport.DeviceLinkTransport

/**
 * Sends the stored space weather on [DataLayerPaths.MESSAGE_SPACE_WEATHER] after each refresh and after sensor
 * pushes (so a watch that was out of reach catches up), skipping a payload identical to the last one sent.
 */
class WatchSpaceWeatherPublisher(
    store: SpaceWeatherStore,
    transport: DeviceLinkTransport,
    scope: CoroutineScope,
    texts: TextResolver,
) : WatchStatePublisher {

    private val delegate = DedupingPayloadPublisher(DataLayerPaths.MESSAGE_SPACE_WEATHER, transport, scope, TAG) {
        store.load()?.let { SpaceWeatherPayload.encode(it, texts) }
    }

    override fun publish() = delegate.publish()

    override suspend fun publishAndWait() = delegate.publishAndWait()

    private companion object {
        const val TAG = "SpaceWeatherPublisher"
    }
}
