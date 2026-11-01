package org.sand.ticwatch.phone.datalayer

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.LinkHold
import org.sand.ticwatch.phone.transport.sendMessage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** `/sync/live` (`{"active": bool}`), mirrored by watch-app's `LiveSyncPayloads`. */
object LiveSyncPayloads {
    private const val ACTIVE = "active"

    fun encode(active: Boolean): ByteArray = JSONObject().put(ACTIVE, active).toString().toByteArray(Charsets.UTF_8)
}

/**
 * Keeps the watch in live sync (a flush every few seconds instead of every 30 min) while the app is on screen: renews
 * the watch's lease every [renewEvery] over the socket [linkHold] keeps open, and ends both on stop. The watch drops
 * a lease that isn't renewed in time on its own, so a killed phone process can't leave it syncing live.
 *
 * APK transfers come first: live sync stops while [setPaused] says so (the System tab, where updates are started) and
 * during any [bulkTransferActive] transfer, which may outlast the tab or come from the deploy script.
 */
class LiveWatchSync(
    private val transport: DeviceLinkTransport,
    private val linkHold: LinkHold,
    private val bulkTransferActive: Flow<Boolean>,
    private val io: CoroutineDispatcher,
    private val renewEvery: Duration = RENEW_EVERY,
) {
    private val paused = MutableStateFlow(false)

    fun setPaused(value: Boolean) {
        paused.value = value
    }

    fun bindTo(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(paused, bulkTransferActive) { isPaused, bulk -> !isPaused && !bulk }
                    .distinctUntilChanged()
                    .collectLatest { live -> if (live) runLive() }
            }
        }
    }

    private suspend fun runLive() {
        try {
            linkHold.hold()
            while (true) {
                send(active = true)
                delay(renewEvery)
            }
        } finally {
            withContext(NonCancellable) {
                send(active = false)
                withContext(io) { linkHold.release() }
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
