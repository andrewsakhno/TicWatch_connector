package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.ExerciseTrack
import org.sand.ticwatch.phone.health.HeartRateZone
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SessionRoutePoints
import org.sand.ticwatch.phone.health.TimeSpan
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One finished workout; [exerciseTypeId] is the Health Services type id, [automatic] = detected, not started by hand. */
data class WorkoutEntry(
    val day: LocalDate,
    val start: Instant,
    val end: Instant,
    val exerciseTypeId: Int,
    val automatic: Boolean,
    val distanceMeters: Double?,
    val zoneMinutes: Map<HeartRateZone, Double>,
)

fun interface WorkoutJournal {
    /** Workouts of today and the six days before it, newest first: the span of the card's weekly zone minutes. */
    suspend fun lastWeek(): List<WorkoutEntry>
}

/**
 * Joins the stored workouts with their derived zone minutes, which carry the workout's start. Distance is the
 * longer of the GPS track and the watch's distance intervals: each can only miss part of a workout (an automatic
 * walk's GPS starts when the walk is detected, its session where the walk began).
 */
class HistoryWorkoutJournal(
    private val reader: SampleWindowReader,
    private val routes: SessionRoutePoints,
    private val clock: Clock,
    private val zone: ZoneId,
) : WorkoutJournal {

    override suspend fun lastWeek(): List<WorkoutEntry> {
        val now = clock.instant()
        val from = now.atZone(zone).toLocalDate().minusDays(WEEK_DAYS - 1).atStartOfDay(zone).toInstant()
        val samples = reader.samples(WORKOUT_TYPES + ZONES_BY_TYPE.keys, from, now, emptyList())
        val zonesByStart = samples.filter { it.type in ZONES_BY_TYPE }.groupBy { it.recordedAt }
        return samples.filter { it.type in WORKOUT_TYPES && it.endedAt != null }
            .sortedByDescending { it.recordedAt }
            .distinctBy { it.recordedAt }
            .map { entry(it, checkNotNull(it.endedAt), zonesByStart[it.recordedAt].orEmpty()) }
    }

    private suspend fun entry(workout: SensorSample, end: Instant, zones: List<SensorSample>) = WorkoutEntry(
        day = workout.recordedAt.atZone(zone).toLocalDate(),
        start = workout.recordedAt,
        end = end,
        exerciseTypeId = workout.value.toInt(),
        automatic = workout.type == SensorType.AUTO_EXERCISE,
        distanceMeters = maxOf(trackMeters(workout.recordedAt, end), watchMeters(workout.recordedAt, end)).takeIf { it > 0 },
        zoneMinutes = zones.associate { ZONES_BY_TYPE.getValue(it.type) to it.value },
    )

    private suspend fun trackMeters(start: Instant, end: Instant): Double = ExerciseTrack(routes.pointsFor(start, end)).meters()

    /** Intervals overlapping the workout, each counted by the share of it inside the workout. */
    private suspend fun watchMeters(start: Instant, end: Instant): Double {
        val workout = TimeSpan(start, end)
        return reader.samples(DISTANCE, start.minus(DISTANCE_INTERVAL_LOOKBACK), end, emptyList()).sumOf { interval ->
            val span = TimeSpan.of(interval)
            interval.value * span.overlap(workout).toMillis() / span.duration.toMillis()
        }
    }

    private companion object {
        val WORKOUT_TYPES = setOf(SensorType.EXERCISE, SensorType.AUTO_EXERCISE)
        val ZONES_BY_TYPE = HeartRateZone.entries.associateBy { it.sensorType }
        val DISTANCE = setOf(SensorType.DISTANCE)

        /** A distance interval beginning before the workout still counts; longer than any passive batch. */
        val DISTANCE_INTERVAL_LOOKBACK: Duration = Duration.ofHours(1)
    }
}
