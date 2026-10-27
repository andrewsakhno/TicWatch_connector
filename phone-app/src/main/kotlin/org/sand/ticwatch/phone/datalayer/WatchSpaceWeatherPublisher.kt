package org.sand.ticwatch.phone.datalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends the stored space weather on [DataLayerPaths.MESSAGE_SPACE_WEATHER] after each refresh and after sensor
 * pushes (so a watch that was out of reach catches up), skipping a payload identical to the last one sent.
 */
class WatchSpaceWeatherPublisher(
    private val store: SpaceWeatherStore,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
) : WatchStatePublisher {

    private val lastSent = AtomicReference<String?>()

    override fun publish() {
        scope.launch {
            val payload = store.payload() ?: return@launch
            if (payload == lastSent.get()) return@launch
            transport.sendMessage(DataLayerPaths.MESSAGE_SPACE_WEATHER, payload.toByteArray(Charsets.UTF_8))
                .onSuccess { lastSent.set(payload) }
                .onFailure { Log.w(TAG, "Sending space weather to the watch failed", it) }
        }
    }

    private companion object {
        const val TAG = "SpaceWeatherPublisher"
    }
}
