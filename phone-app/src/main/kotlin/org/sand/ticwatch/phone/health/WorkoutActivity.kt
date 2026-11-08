package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.history.WorkoutSession
import org.sand.ticwatch.phone.history.WorkoutSessionQueries
import java.time.Duration
import java.time.Instant

/**
 * The activity the rest calculations exclude: workouts and the [RECOVERY] after them, while the heart rate is still up.
 * Raw steps don't count: a walk long enough to matter becomes a workout after the auto-workout delay set for the watch.
 */
class WorkoutActivity(workouts: List<Span>) {

    /** One workout; [steps] = what it counted, 0 for one known only from a raw row not stored yet. */
    data class Span(val start: Instant, val end: Instant, val steps: Double)

    private val spans = workouts.sortedBy { it.start }

    /** Inside a workout or its recovery. */
    fun isActive(at: Instant): Boolean = spans.any { !at.isBefore(it.start) && !at.isAfter(it.end.plus(RECOVERY)) }

    /** Whether any part of [from, to) is active. */
    fun overlaps(from: Instant, to: Instant): Boolean = spans.any { it.start.isBefore(to) && it.end.plus(RECOVERY).isAfter(from) }

    /** Workout steps inside [from, to), each workout by its overlapping share. */
    fun stepsIn(from: Instant, to: Instant): Double = spans.sumOf { span ->
        val length = Duration.between(span.start, span.end).toMillis()
        val overlap = Duration.between(maxOf(span.start, from), minOf(span.end, to)).toMillis()
        if (length <= 0 || overlap <= 0) 0.0 else span.steps * overlap / length
    }

    /** Adds workouts not stored yet (a push before it is written), without their steps; a stored one wins. */
    fun withSamples(samples: List<SensorSample>): WorkoutActivity {
        val known = spans.mapTo(mutableSetOf()) { it.start }
        val pushed = samples.filter { it.type in WorkoutSession.SAMPLE_TYPES && it.recordedAt !in known }
            .map { Span(it.recordedAt, it.endedAt ?: it.recordedAt.plus(LONGEST_UNFINISHED), NO_STEPS) }
        return if (pushed.isEmpty()) this else WorkoutActivity(spans + pushed)
    }

    companion object {
        /** The heart rate stays up after a workout; the body temperature estimator waits as long. */
        val RECOVERY: Duration = Duration.ofMinutes(30)

        private const val NO_STEPS = 0.0

        /** An unfinished workout is never ended: it stays active for at most this long after its start. */
        val LONGEST_UNFINISHED: Duration = Duration.ofHours(12)

        fun of(sessions: List<WorkoutSession>) = WorkoutActivity(
            sessions.map { Span(it.start, it.end ?: it.start.plus(LONGEST_UNFINISHED), it.steps ?: NO_STEPS) },
        )

        fun ofSamples(samples: List<SensorSample>) = WorkoutActivity(emptyList()).withSamples(samples)
    }
}

/** Reads the [WorkoutActivity] of a time range from the stored workouts, which outlive the raw rows. */
class WorkoutActivitySource(private val queries: WorkoutSessionQueries) {

    /** Workouts whose activity reaches into [from, to): a recovery tail counts too. */
    suspend fun activity(from: Instant, to: Instant): WorkoutActivity =
        WorkoutActivity.of(queries.workoutSessions(from.minus(WorkoutActivity.RECOVERY), to))
}
