package org.sand.ticwatch.phone.datalayer

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `/sync/live` (`{"active": bool}`), mirrored by watch-app's `LiveSyncPayloads`. */
object LiveSyncPayloads {
    private const val ACTIVE = "active"

    fun encode(active: Boolean): ByteArray = JSONObject().put(ACTIVE, active).toString().toByteArray(Charsets.UTF_8)
}

/**
 * Keeps the watch in live sync (a flush every few seconds instead of every 30 min) while [owner] is started, i.e.
 * the app is on screen: renews the watch's lease every [renewEvery] and ends it on stop. The watch drops a lease
 * that isn't renewed in time on its own, so a killed phone process can't leave it syncing live.
 */
class LiveWatchSync(
    private val transport: DeviceLinkTransport,
    private val io: CoroutineDispatcher,
    private val renewEvery: Duration = RENEW_EVERY,
) {
    fun bindTo(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    while (true) {
                        send(active = true)
                        delay(renewEvery)
                    }
                } finally {
                    withContext(NonCancellable) { send(active = false) }
                }
            }
        }
    }

    private suspend fun send(active: Boolean) = withContext(io) {
        transport.sendMessage(DataLayerPaths.MESSAGE_LIVE_SYNC, LiveSyncPayloads.encode(active))
            .onFailure { Log.w(TAG, "Live sync ${if (active) "renewal" else "stop"} not delivered", it) }
    }

    private companion object {
        const val TAG = "LiveWatchSync"

        /** Well inside the watch's lease, so one lost renewal doesn't end live sync. */
        val RENEW_EVERY: Duration = 30.seconds
    }
}
