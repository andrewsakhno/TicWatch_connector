package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.ExerciseSessionRecord

/**
 * Wire contract with watch-app: an EXERCISE sample's value is the Health Services
 * `ExerciseType.id` (watch-side library, not a phone dependency), mirrored here and
 * mapped onto Health Connect's exercise-type constants. Unlisted ids fall back to
 * [ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT].
 */
object HealthServicesExerciseTypes {
    private const val BADMINTON = 2
    private const val BASKETBALL = 5
    private const val BIKING = 8
    private const val BIKING_STATIONARY = 9
    private const val DANCING = 16
    private const val ELLIPTICAL = 25
    private const val GOLF = 32
    private const val HIGH_INTENSITY_INTERVAL_TRAINING = 36
    private const val HIKING = 37
    private const val PILATES = 48
    private const val ROWING = 53
    private const val ROWING_MACHINE = 54
    private const val RUNNING = 55
    private const val RUNNING_TREADMILL = 56
    private const val SKIING = 61
    private const val SOCCER = 64
    private const val STAIR_CLIMBING = 68
    private const val STAIR_CLIMBING_MACHINE = 69
    private const val STRENGTH_TRAINING = 70
    private const val STRETCHING = 71
    private const val SWIMMING_OPEN_WATER = 73
    private const val SWIMMING_POOL = 74
    private const val TENNIS = 76
    private const val WALKING = 79
    private const val WEIGHTLIFTING = 81
    private const val YOGA = 83

    private val TO_HEALTH_CONNECT = mapOf(
        BADMINTON to ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
        BASKETBALL to ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
        BIKING to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
        BIKING_STATIONARY to ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
        DANCING to ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
        ELLIPTICAL to ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
        GOLF to ExerciseSessionRecord.EXERCISE_TYPE_GOLF,
        HIGH_INTENSITY_INTERVAL_TRAINING to ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
        HIKING to ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        PILATES to ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
        ROWING to ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ROWING_MACHINE to ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
        RUNNING to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        RUNNING_TREADMILL to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
        SKIING to ExerciseSessionRecord.EXERCISE_TYPE_SKIING,
        SOCCER to ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
        STAIR_CLIMBING to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
        STAIR_CLIMBING_MACHINE to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
        STRENGTH_TRAINING to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
        STRETCHING to ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
        SWIMMING_OPEN_WATER to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
        SWIMMING_POOL to ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        TENNIS to ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
        WALKING to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        WEIGHTLIFTING to ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
        YOGA to ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
    )

    fun toHealthConnect(healthServicesId: Int): Int =
        TO_HEALTH_CONNECT[healthServicesId] ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}
