package org.sand.ticwatch.phone.health

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.exp

/**
 * Karvonen heart-rate zones as shares of the heart-rate reserve, each starting at [minReserveFraction];
 * [sensorType] stores the minutes a workout spent in the zone.
 */
enum class HeartRateZone(val number: Int, val minReserveFraction: Double, val sensorType: SensorType) {
    ZONE_1(1, 0.50, SensorType.HEART_RATE_ZONE_1),
    ZONE_2(2, 0.60, SensorType.HEART_RATE_ZONE_2),
    ZONE_3(3, 0.70, SensorType.HEART_RATE_ZONE_3),
    ZONE_4(4, 0.80, SensorType.HEART_RATE_ZONE_4),
    ZONE_5(5, 0.90, SensorType.HEART_RATE_ZONE_5);

    companion object {
        /** The highest zone [reserveFraction] reaches, or null below zone 1. */
        fun forReserve(reserveFraction: Double): HeartRateZone? = entries.lastOrNull { reserveFraction >= it.minReserveFraction }
    }
}

/** Banister's TRIMP weighting factor, which differs by sex; [MALE] is Banister's original and the default. */
enum class TrimpCoefficient(val factor: Double) {
    MALE(1.92),
    FEMALE(1.67);

    companion object {
        fun of(sex: BiologicalSex?): TrimpCoefficient = if (sex == BiologicalSex.FEMALE) FEMALE else MALE
    }
}

/** One workout's time per [HeartRateZone] and its Banister training impulse. */
data class ExerciseLoad(val zoneDurations: Map<HeartRateZone, Duration>, val trimp: Double)

/**
 * Integrates a workout's heart rate: each reading counts until the next one (at most [maxReadingSpan], so
 * a dropped sensor doesn't stretch one reading over a gap) and adds its time to its Karvonen zone and
 * `minutes * r * 0.64 * e^(k * r)` to the TRIMP, r being the heart-rate reserve fraction (clamped at 0).
 */
class ExerciseLoadCalculator(private val maxReadingSpan: Duration = Duration.ofSeconds(30)) {

    fun load(
        heartRate: List<SensorSample>,
        end: Instant,
        restingBpm: Double,
        maxBpm: Double,
        coefficient: TrimpCoefficient,
    ): ExerciseLoad {
        val zones = mutableMapOf<HeartRateZone, Duration>()
        var trimp = 0.0
        val readings = heartRate.filter { it.value > 0 }.sortedBy { it.recordedAt }
        readings.forEachIndexed { index, reading ->
            val next = readings.getOrNull(index + 1)?.recordedAt ?: end
            val span = Duration.between(reading.recordedAt, next).coerceIn(Duration.ZERO, maxReadingSpan)
            val reserve = ((reading.value - restingBpm) / (maxBpm - restingBpm)).coerceAtLeast(0.0)
            HeartRateZone.forReserve(reserve)?.let { zone -> zones[zone] = (zones[zone] ?: Duration.ZERO) + span }
            trimp += span.toMillis() / MILLIS_PER_MINUTE * reserve * TRIMP_SCALE * exp(coefficient.factor * reserve)
        }
        return ExerciseLoad(zones, trimp)
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
        const val TRIMP_SCALE = 0.64
    }
}

/**
 * Cardio load of every finished workout (watch EXERCISE and phone-detected AUTO_EXERCISE) in a push:
 * one [SensorType.TRAINING_LOAD] interval (TRIMP) and one [HeartRateZone.sensorType] interval per zone
 * reached (minutes), all spanning the workout, so the day rollups keep daily load and zone time forever.
 * Needs the birth year (age-predicted max HR) and a resting HR; [log] keeps a re-sent workout from
 * counting twice.
 */
class CardioLoadDeriver(
    private val reader: SampleWindowReader,
    private val profile: UserProfileStore,
    private val restingHeartRate: RestingHeartRateBaseline,
    private val calculator: ExerciseLoadCalculator,
    private val log: InstantLog,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val lastCounted = log.last()
        val workouts = samples.filter { it.type in WORKOUT_TYPES && it.endedAt != null }
            .filter { lastCounted == null || it.recordedAt.isAfter(lastCounted) }
            .sortedBy { it.recordedAt }
        if (workouts.isEmpty()) return emptyList()
        val birthYear = profile.birthYear() ?: return emptyList()
        val coefficient = TrimpCoefficient.of(profile.sex())
        val derived = workouts.flatMap { workout ->
            val end = checkNotNull(workout.endedAt)
            val resting = restingHeartRate.bpm(workout.recordedAt, samples) ?: return@flatMap emptyList()
            val max = TanakaMaxHeartRate.bpm(workout.recordedAt.atZone(zoneId).year - birthYear)
            if (max <= resting) return@flatMap emptyList()
            val heartRate = reader.samples(HEART_RATE, workout.recordedAt, end, samples)
            if (heartRate.isEmpty()) return@flatMap emptyList()
            val load = calculator.load(heartRate, end, resting, max, coefficient)
            listOf(SensorSample(SensorType.TRAINING_LOAD, load.trimp, workout.recordedAt, end)) +
                load.zoneDurations.map { (zone, time) ->
                    SensorSample(zone.sensorType, time.toMillis() / MILLIS_PER_MINUTE, workout.recordedAt, end)
                }
        }
        log.record(workouts.last().recordedAt)
        return derived
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
        val WORKOUT_TYPES = setOf(SensorType.EXERCISE, SensorType.AUTO_EXERCISE)
        val HEART_RATE = setOf(SensorType.HEART_RATE)
    }
}
