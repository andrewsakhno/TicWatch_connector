package org.sand.ticwatch.watch.route

import android.util.Log
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.sensors.UserActivityObserver
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/**
 * Records the watch's own GPS track while a Health Services workout runs, so the route doesn't
 * depend on the phone being carried. Fixes go to the phone in batches of [batchSize] during the
 * workout and the rest right when it ends - before the EXERCISE sample's next sync flush, since
 * phone-app joins the points to the session by time when it writes the record. A failed batch
 * stays pending and rides along with the next one; the phone de-duplicates by fix time.
 */
class WatchRouteRecorder(
    private val fixSource: LocationFixSource,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : UserActivityObserver {

    private val pendingLock = Any()
    private val pending = mutableListOf<RouteFix>()
    private val sendMutex = Mutex()
    private var recording = false

    override fun onUserActivity(info: UserActivityInfo) {
        val isActive = info.userActivityState == UserActivityState.USER_ACTIVITY_EXERCISE
        if (isActive == recording) return
        recording = isActive
        if (isActive) {
            fixSource.start(::onFix)
        } else {
            fixSource.stop()
            scope.launch { flushWithRetry() }
        }
    }

    private fun onFix(fix: RouteFix) {
        val batchFull = synchronized(pendingLock) {
            pending += fix
            pending.size >= batchSize
        }
        if (batchFull) scope.launch { flushOnce() }
    }

    /** Sends everything pending; true when nothing is left to send. */
    private suspend fun flushOnce(): Boolean = sendMutex.withLock {
        val batch = synchronized(pendingLock) { pending.toList() }
        if (batch.isEmpty()) return@withLock true
        val sent = transport.sendMessage(DataLayerPaths.MESSAGE_EXERCISE_ROUTE_PUSH, WorkoutPayloads.routePush(batch)).isSuccess
        // Fixes are only ever appended, so the sent batch is still the list's prefix.
        if (sent) synchronized(pendingLock) { pending.subList(0, batch.size).clear() }
        if (sent) Log.i(TAG, "Sent ${batch.size} route fixes") else Log.w(TAG, "Failed to send ${batch.size} route fixes")
        sent
    }

    private suspend fun flushWithRetry() {
        repeat(MAX_ATTEMPTS) { attempt ->
            if (flushOnce()) return
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        Log.w(TAG, "Gave up sending the workout's last route fixes after $MAX_ATTEMPTS attempts")
    }

    private companion object {
        const val TAG = "WatchRouteRecorder"

        /** ~4 min of fixes at the 4 s interval, ~8 KB of JSON: far below the resumable-frame threshold. */
        const val DEFAULT_BATCH_SIZE = 60
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 10_000L
    }
}
