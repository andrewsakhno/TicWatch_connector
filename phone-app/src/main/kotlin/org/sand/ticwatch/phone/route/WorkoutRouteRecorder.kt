package org.sand.ticwatch.phone.route

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * when "Allow all the time" (ACCESS_BACKGROUND_LOCATION) is granted; without it the start is
 * refused and logged, and the workout is still written - just without a phone route.
 */
class ServiceWorkoutRouteRecorder(private val context: Context) : WorkoutRouteRecorder {

    override fun onWorkoutStarted() {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Workout started on the watch, but location permission is missing; no phone route")
            return
        }
        runCatching { ContextCompat.startForegroundService(context, WorkoutLocationService.startIntent(context)) }
            .onFailure { Log.w(TAG, "Android refused to start route tracking from the background", it) }
    }

    override fun onWorkoutEnded() {
        // startService, not startForegroundService: the stop intent never calls startForeground.
        runCatching { context.startService(WorkoutLocationService.stopIntent(context)) }
            .onFailure { Log.w(TAG, "Failed to stop route tracking", it) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "WorkoutRouteRecorder"
    }
}
