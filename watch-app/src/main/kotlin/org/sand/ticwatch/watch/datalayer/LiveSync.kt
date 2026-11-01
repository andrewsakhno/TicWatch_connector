package org.sand.ticwatch.watch.datalayer

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import org.json.JSONObject
import org.sand.ticwatch.watch.phonedata.PhonePayloadSink
import java.time.Duration

/** `/sync/live` (`{"active": bool}`), mirrored by phone-app's `LiveSyncPayloads`. */
object LiveSyncPayloads {
    private const val ACTIVE = "active"

    fun decodeActive(payload: String): Boolean? =
        runCatching { JSONObject(payload).getBoolean(ACTIVE) }.getOrNull()
}

/** Whether the phone app is on screen and wants samples every few seconds rather than in the 30 min batches. */
interface LiveSyncSignal {
    fun isActive(): Boolean

    /** The current state, then every change, a lapsed lease included. */
    val active: Flow<Boolean>
}

/**
 * The phone's live-sync request as a lease: each `active` message extends it by [lease], an inactive one ends it,
 * and one not renewed in time (phone process killed, link lost) lapses on its own, so the watch never stays in
 * battery-heavy live sync without a phone asking for it. Counted on the elapsed-realtime clock, which keeps
 * running while the watch is suspended.
 */
class LiveSyncLease(
    private val lease: Duration,
    private val label: String = LIVE_SYNC_LABEL,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) : LiveSyncSignal, PhonePayloadSink {
    private val expiresAt = MutableStateFlow(NOT_LEASED)

    override fun isActive(): Boolean = elapsedMillis() < expiresAt.value

    @OptIn(ExperimentalCoroutinesApi::class)
    override val active: Flow<Boolean> = expiresAt.transformLatest { expiry ->
        val remaining = expiry - elapsedMillis()
        emit(remaining > 0)
        if (remaining > 0) {
            delay(remaining)
            emit(false)
        }
    }.distinctUntilChanged()

    override fun accept(payload: String) {
        val active = LiveSyncPayloads.decodeActive(payload)
        if (active == null) {
            Log.w(TAG, "Malformed live-sync payload")
            return
        }
        val wasActive = isActive()
        expiresAt.value = if (active) elapsedMillis() + lease.toMillis() else NOT_LEASED
        if (active != wasActive) Log.i(TAG, "$label ${if (active) "started" else "stopped"} by the phone")
    }

    companion object {
        private const val TAG = "LiveSyncLease"
        private const val NOT_LEASED = 0L

        /** The phone renews every 30 s; this survives one lost renewal. */
        private val LEASE: Duration = Duration.ofSeconds(75)
        private const val LIVE_SYNC_LABEL = "Live sync"

        /** The phone renews every 2 min while it gets good fixes; this survives one lost renewal. */
        private val PHONE_ROUTE_TRACK_LEASE: Duration = Duration.ofMinutes(5)
        private const val PHONE_ROUTE_TRACK_LABEL = "Phone route track"

        /** One per process: the link services write it, the sensor service's sync sender reads it. */
        val processWide: LiveSyncLease by lazy { LiveSyncLease(LEASE) }

        /** The same lease on `/workout/phone-track`: the phone is tracking the workout, so the watch's GPS can rest. */
        val phoneRouteTrack: LiveSyncLease by lazy { LiveSyncLease(PHONE_ROUTE_TRACK_LEASE, PHONE_ROUTE_TRACK_LABEL) }
    }
}
