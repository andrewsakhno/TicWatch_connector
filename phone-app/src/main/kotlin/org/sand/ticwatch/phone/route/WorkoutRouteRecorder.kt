package org.sand.ticwatch.phone.route

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat

/** Reacts to the watch's workout-state messages by recording (or not) a phone-side GPS track. */
interface WorkoutRouteRecorder {
    fun onWorkoutStarted()
    fun onWorkoutEnded()
}

/**
 * [WorkoutRouteRecorder] driving [WorkoutLocationService]. The start arrives while the phone app
 * is usually in the background, so Android only lets it start a `location` foreground service
 * when "Allow all the time" (ACCESS_BACKGROUND_LOCATION) is granted. [gate] pre-checks that; any
 * refusal Android still raises is caught and logged - the workout itself is still tracked and
 * written, just without a phone route.
 */
class ServiceWorkoutRouteRecorder(
    private val context: Context,
    private val gate: RouteTrackingGate,
) : WorkoutRouteRecorder {

    override fun onWorkoutStarted() {
        gate.blocker()?.let {
            Log.w(TAG, "Workout started on the watch, but phone route tracking is blocked ($it); skipping GPS route")
            return
        }
        try {
            ContextCompat.startForegroundService(context, WorkoutLocationService.startIntent(context))
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission for location route tracking; skipping GPS route", e)
        } catch (e: IllegalStateException) {
            // Covers ForegroundServiceStartNotAllowedException (API 31+) and background start limits.
            Log.w(TAG, "Android refused to start route tracking from the background; skipping GPS route", e)
        }
    }

    override fun onWorkoutEnded() {
        // startService, not startForegroundService: the stop intent never calls startForeground.
        runCatching { context.startService(WorkoutLocationService.stopIntent(context)) }
            .onFailure { Log.w(TAG, "Failed to stop route tracking", it) }
    }

    private companion object {
        const val TAG = "WorkoutRouteRecorder"
    }
}
