package org.sand.ticwatch.watch.autoworkout

import android.content.SharedPreferences
import android.util.Log
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.startExercise
import java.time.Duration
import java.time.Instant

/** Who runs the watch's current Health Services workout, if anyone. */
enum class WorkoutOwner { NONE, THIS_APP, OTHER_APP }

/** The watch's system workout, as far as this app may drive it. */
interface WorkoutSession {
    suspend fun owner(): WorkoutOwner

    /** Starts a walk; false when the watch can't track one. */
    suspend fun startWalk(): Boolean

    /** Ends the workout this app started. */
    suspend fun end()
}

/**
 * [WorkoutSession] over Health Services' [ExerciseClient] - the same workout other Wear apps run, so
 * the passive user-activity state turns EXERCISE and the existing session/route pipeline records it.
 * GPS is left to [org.sand.ticwatch.watch.route.WatchRouteRecorder], which follows that state anyway.
 */
class HealthServicesWorkoutSession(private val exerciseClient: ExerciseClient) : WorkoutSession {

    override suspend fun owner(): WorkoutOwner = when (exerciseClient.getCurrentExerciseInfo().exerciseTrackedStatus) {
        ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> WorkoutOwner.THIS_APP
        ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> WorkoutOwner.OTHER_APP
        else -> WorkoutOwner.NONE
    }

    override suspend fun startWalk(): Boolean {
        val capabilities = exerciseClient.getCapabilities()
        if (ExerciseType.WALKING !in capabilities.supportedExerciseTypes) {
            Log.w(TAG, "Health Services can't track walking on this watch")
            return false
        }
        val supported = capabilities.getExerciseTypeCapabilities(ExerciseType.WALKING).supportedDataTypes
        val config = ExerciseConfig.builder(ExerciseType.WALKING)
            .setDataTypes(WALK_DATA_TYPES.filter { it in supported }.toSet())
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        exerciseClient.startExercise(config)
        return true
    }

    override suspend fun end() {
        exerciseClient.endExercise()
    }

    private companion object {
        const val TAG = "HealthServicesWorkout"
        val WALK_DATA_TYPES = listOf(DataType.HEART_RATE_BPM, DataType.STEPS, DataType.DISTANCE, DataType.CALORIES)
    }
}

/**
 * Remembers where the walk behind an automatic workout began, so its EXERCISE session can start there
 * instead of at the moment the detector was sure (a workout can't be started in the past).
 */
class AutoWorkoutStartLedger(
    private val preferences: SharedPreferences,
    private val maxBackdate: Duration,
) {

    fun record(walkStart: Instant) {
        preferences.edit().putLong(KEY_WALK_START, walkStart.toEpochMilli()).apply()
    }

    /** Session start for [info]: the recorded walk start for this app's own workout, else the state change. */
    fun sessionStart(info: UserActivityInfo): Instant {
        val stateChange = info.stateChangeTime
        if (info.exerciseInfo?.exerciseTrackedStatus != ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) return stateChange
        if (!preferences.contains(KEY_WALK_START)) return stateChange
        val walkStart = Instant.ofEpochMilli(preferences.getLong(KEY_WALK_START, 0L))
        preferences.edit().remove(KEY_WALK_START).apply()
        val backdate = Duration.between(walkStart, stateChange)
        return if (backdate.isNegative || backdate > maxBackdate) stateChange else walkStart
    }

    companion object {
        const val PREFERENCES_NAME = "auto_workout_start"
        private const val KEY_WALK_START = "walk_start_epoch_ms"
    }
}
