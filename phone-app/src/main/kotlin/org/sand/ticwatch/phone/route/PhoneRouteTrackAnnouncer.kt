package org.sand.ticwatch.phone.route

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.LiveSyncPayloads
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.Duration

/**
 * Tells the watch its GPS can rest: every [renewEvery], as long as fixes at least as accurate as [maxAccuracyMeters]
 * keep coming. There is no "stop" message - when the fixes end or the watch is out of reach, its lease simply lapses
 * and its own GPS takes over. A failed send also waits [renewEvery]: the phone may well be out of Bluetooth range.
 */
class PhoneRouteTrackAnnouncer(
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val renewEvery: Duration = RENEW_EVERY,
    private val maxAccuracyMeters: Double = MAX_ACCURACY_METERS,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastAttemptAt: Long? = null

    fun onFix(point: RoutePoint) {
        val accuracy = point.horizontalAccuracyMeters ?: return
        if (accuracy > maxAccuracyMeters) return
        val now = elapsedMillis()
        if (lastAttemptAt?.let { now - it < renewEvery.toMillis() } == true) return
        lastAttemptAt = now
        scope.launch {
            transport.sendMessage(DataLayerPaths.MESSAGE_PHONE_ROUTE_TRACK, LiveSyncPayloads.encode(true))
                .onFailure { Log.i(TAG, "Watch not told about the phone's route track: $it") }
        }
    }

    private companion object {
        const val TAG = "PhoneRouteTrack"

        /** Well inside the watch's 5 min lease, so one lost renewal doesn't start its GPS. */
        val RENEW_EVERY: Duration = Duration.ofMinutes(2)

        /** Coarser fixes (indoors, Wi-Fi positioning) make a poor route; the watch's GPS stays on for those. */
        const val MAX_ACCURACY_METERS = 30.0
    }
}
