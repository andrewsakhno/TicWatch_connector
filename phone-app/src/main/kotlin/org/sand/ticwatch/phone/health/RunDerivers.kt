package org.sand.ticwatch.phone.health

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Ended outdoor runs among [samples]: the sessions whose GPS track the run derivers read. */
private fun runs(samples: List<SensorSample>): List<Pair<Instant, Instant>> =
    samples.filter { it.type == SensorType.EXERCISE && it.value.toInt() == HealthServicesExerciseTypes.RUNNING }
        .mapNotNull { sample -> sample.endedAt?.let { sample.recordedAt to it } }

/** Plausible running pace (m/s); slower is standing at a crossing, faster a GPS jump. */
private val RUNNING_SPEED = 1.5..7.0

/** GPS altitude is noisy: grades beyond this are treated as noise, not hills. */
private const val MAX_GRADE = 0.3

/**
 * Running power (W) per [segment] of an outdoor run's GPS track: `m * v * (C + g * grade)`, the energy
 * cost of running on the flat ([COST_OF_RUNNING], J/kg/m - the convention running power meters use) plus
 * the work of climbing. Descents lower it, never below zero. Needs the profile weight.
 */
class RunningPowerDeriver(
    private val routePoints: SessionRoutePoints,
    private val profile: UserProfileStore,
    private val segment: Duration = Duration.ofSeconds(15),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val sessions = runs(samples)
        if (sessions.isEmpty()) return emptyList()
        val weightKg = profile.weightKg() ?: return emptyList()
        return sessions.flatMap { (start, end) ->
            ExerciseTrack(routePoints.pointsFor(start, end)).segments(segment)
                .filter { it.speed in RUNNING_SPEED }
                .map { part ->
                    val grade = part.grade.coerceIn(-MAX_GRADE, MAX_GRADE)
                    val watts = (weightKg * part.speed * (COST_OF_RUNNING + GRAVITY * grade)).coerceAtLeast(0.0)
                    SensorSample(SensorType.POWER, watts, part.end)
                }
        }
    }

    private companion object {
        const val COST_OF_RUNNING = 1.04
        const val GRAVITY = 9.81
    }
}

/**
 * VO2 max from an outdoor run: on steady, flat [segment]s past the [warmUp] (heart rate lags pace),
 * the ACSM running equation gives the oxygen cost of the pace, and %VO2 reserve ~ %HR reserve (Swain)
 * scales it up to the maximum: `VO2max = 3.5 + (VO2 - 3.5) / HRR`. The median over at least
 * [minSegments] segments at [RESERVE_RANGE] effort is the estimate - a field-test style value Health
 * Connect files under `MEASUREMENT_METHOD_OTHER`, next to the daily resting-HR ratio one.
 */
class RunVo2MaxDeriver(
    private val routePoints: SessionRoutePoints,
    private val reader: SampleWindowReader,
    private val profile: UserProfileStore,
    private val restingHeartRate: RestingHeartRateBaseline,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val segment: Duration = Duration.ofSeconds(30),
    private val warmUp: Duration = Duration.ofMinutes(5),
    private val minSegments: Int = 6,
    private val minHeartRateReadings: Int = 3,
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val sessions = runs(samples)
        if (sessions.isEmpty()) return emptyList()
        val birthYear = profile.birthYear() ?: return emptyList()
        return sessions.mapNotNull { (start, end) ->
            val resting = restingHeartRate.bpm(start, samples) ?: return@mapNotNull null
            val max = TanakaMaxHeartRate.bpm(start.atZone(zoneId).year - birthYear)
            if (max <= resting) return@mapNotNull null
            val heartRate = reader.samples(HEART_RATE, start, end, samples)
            val estimates = ExerciseTrack(routePoints.pointsFor(start, end)).segments(segment)
                .filter { !it.start.isBefore(start.plus(warmUp)) && it.speed in RUNNING_SPEED && abs(it.grade) <= MAX_FLAT_GRADE }
                .mapNotNull { part ->
                    val bpm = heartRate.filter { !it.recordedAt.isBefore(part.start) && it.recordedAt.isBefore(part.end) }
                    if (bpm.size < minHeartRateReadings) return@mapNotNull null
                    val reserve = (bpm.map { it.value }.average() - resting) / (max - resting)
                    if (reserve !in RESERVE_RANGE) return@mapNotNull null
                    val metersPerMinute = part.speed * SECONDS_PER_MINUTE
                    val vo2 = RESTING_VO2 + HORIZONTAL_COST * metersPerMinute + VERTICAL_COST * metersPerMinute * part.grade
                    RESTING_VO2 + (vo2 - RESTING_VO2) / reserve
                }
            if (estimates.size < minSegments) return@mapNotNull null
            SensorSample(SensorType.VO2_MAX_EXERCISE, estimates.sorted()[estimates.size / 2], end)
        }
    }

    private companion object {
        val HEART_RATE = setOf(SensorType.HEART_RATE)
        val RESERVE_RANGE = 0.5..0.95
        const val MAX_FLAT_GRADE = 0.03
        const val SECONDS_PER_MINUTE = 60.0
        const val RESTING_VO2 = 3.5
        const val HORIZONTAL_COST = 0.2
        const val VERTICAL_COST = 0.9
    }
}
