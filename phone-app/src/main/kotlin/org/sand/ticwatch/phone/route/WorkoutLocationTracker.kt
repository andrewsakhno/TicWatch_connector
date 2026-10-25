package org.sand.ticwatch.phone.route

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Records the phone's GPS track while a workout runs on the watch: [start] begins collecting
 * [LocationSource] fixes into [routeLog], [stop] ends it. Fixes are stored as they arrive and
 * joined to the watch's EXERCISE session by time when that session is written to Health
 * Connect, so nothing has to be linked at stop time. Idempotent: a repeated start is ignored.
 */
class WorkoutLocationTracker(
    private val locationSource: LocationSource,
    private val routeLog: ExerciseRouteLog,
    private val scope: CoroutineScope,
    private val interval: Duration = DEFAULT_INTERVAL,
) {
    private var tracking: Job? = null

    val isTracking: Boolean get() = tracking?.isActive == true

    fun start() {
        if (isTracking) return
        tracking = scope.launch {
            locationSource.fixes(interval)
                .catch { error -> Log.w(TAG, "Location updates failed", error) }
                .collect { point -> runCatching { routeLog.insert(listOf(point)) }.onFailure { Log.w(TAG, "Failed to store fix", it) } }
        }
        Log.i(TAG, "Workout route tracking started")
    }

    fun stop() {
        tracking?.cancel()
        tracking = null
        Log.i(TAG, "Workout route tracking stopped")
    }

    private companion object {
        const val TAG = "WorkoutLocationTracker"
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(4)
    }
}
