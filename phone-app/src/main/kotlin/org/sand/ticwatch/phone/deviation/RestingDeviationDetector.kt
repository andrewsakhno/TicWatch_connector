package org.sand.ticwatch.phone.deviation

import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.WorkoutActivity
import java.time.Duration
import java.time.Instant

private fun SensorSample.endOrStart(): Instant = endedAt ?: recordedAt

/**
 * Finds stretches of heart rate outside the [HeartRateCorridors] while at rest: outside every workout and its recovery
 * ([WorkoutActivity]); raw steps aren't read, a walk long enough to matter is a workout. Readings right after the watch
 * was put back on don't count; every value here is preliminary and to be tuned by the number of episodes on real data.
 * Pure: the same readings always give the same candidates, so re-scanning a range is safe.
 */
class RestingDeviationDetector {

    /** [heartRate] readings, ascending or not; the others are the context a reading is judged in. */
    fun detect(
        heartRate: List<SensorSample>,
        activity: WorkoutActivity,
        sleep: List<SensorSample>,
        corridors: HeartRateCorridors,
    ): List<DeviationCandidate> {
        val candidates = mutableListOf<DeviationCandidate>()
        var run: Run? = null
        fun closeRun() {
            run?.toCandidate()?.let(candidates::add)
            run = null
        }
        var previous: Instant? = null
        var wornSince: Instant? = null
        heartRate.sortedBy { it.recordedAt }.forEach { reading ->
            val at = reading.recordedAt
            // The first reading has no predecessor: unknown, so not treated as a fresh put-on.
            if (previous != null && Duration.between(previous, at) >= WEAR_GAP) wornSince = at
            previous = at
            val state = restStateAt(at, sleep)
            val corridor = corridors.of(state)
            val direction = corridor.directionOf(reading.value)
            val open = run
            when {
                !isAtRest(at, activity) || isSettling(at, wornSince) -> closeRun()
                direction == null -> if (open != null && Duration.between(open.lastOutside, at) > NORMAL_BLIP) closeRun()
                open == null -> run = Run(direction, at, wornSince).also { it.add(reading, state, corridor) }
                open.direction != direction || Duration.between(open.lastOutside, at) > MAX_READING_GAP -> {
                    closeRun()
                    run = Run(direction, at, wornSince).also { it.add(reading, state, corridor) }
                }
                else -> open.add(reading, state, corridor)
            }
        }
        closeRun()
        return candidates
    }

    private fun isSettling(at: Instant, wornSince: Instant?): Boolean =
        wornSince != null && Duration.between(wornSince, at) < PUT_ON_SETTLE

    private fun restStateAt(at: Instant, sleep: List<SensorSample>): RestState =
        if (sleep.any { !at.isBefore(it.recordedAt) && at.isBefore(it.endOrStart()) }) RestState.SLEEP else RestState.AWAKE_REST

    private fun isAtRest(at: Instant, activity: WorkoutActivity): Boolean = !activity.isActive(at)

    private class Run(val direction: DeviationDirection, val start: Instant, val wornSince: Instant?) {
        var lastOutside: Instant = start
            private set
        private var peak: Double? = null
        private var threshold: Double = 0.0
        private var baseline: Double? = null
        private var excessBpmMinutes = 0.0
        private var readings = 0
        private var asleep = 0

        fun add(reading: SensorSample, state: RestState, corridor: HeartRateCorridor) {
            val edge = corridor.thresholdOf(direction)
            val step = Duration.between(lastOutside, reading.recordedAt).coerceAtMost(MAX_READING_GAP)
            excessBpmMinutes += direction.excessOf(reading.value, edge) * step.toMillis() / MILLIS_PER_MINUTE
            lastOutside = reading.recordedAt
            val current = peak
            if (current == null || direction.peakOf(current, reading.value) != current) {
                peak = reading.value
                threshold = edge
                baseline = corridor.baselineBpm
            }
            readings++
            if (state == RestState.SLEEP) asleep++
        }

        fun toCandidate(): DeviationCandidate? {
            val value = peak ?: return null
            if (readings < MIN_READINGS || Duration.between(start, lastOutside) < MIN_HOLD) return null
            val state = if (asleep * 2 >= readings) RestState.SLEEP else RestState.AWAKE_REST
            return DeviationCandidate(direction, state, start, lastOutside, value, threshold, baseline, excessBpmMinutes, wornSince)
        }
    }

    companion object {
        private const val MIN_READINGS = 3
        private const val MILLIS_PER_MINUTE = 60_000.0

        /** Silence this long between readings means the watch was off the wrist (no steps or skin temperature either). */
        val WEAR_GAP: Duration = Duration.ofMinutes(10)

        /** The first readings after putting the watch on run high: arm movement, strap settling. */
        private val PUT_ON_SETTLE: Duration = Duration.ofMinutes(10)

        /** Short enough to catch brief stress or positional episodes. */
        private val MIN_HOLD: Duration = Duration.ofMinutes(3)

        /** Passive readings arrive every 1-30 s; a longer silence says nothing about what happened in between. */
        private val MAX_READING_GAP: Duration = Duration.ofMinutes(3)

        /** A lone normal reading inside a stretch is sensor noise, not a return to the range. */
        private val NORMAL_BLIP: Duration = Duration.ofMinutes(1)

        /** The heart rate stays up after a workout; see [WorkoutActivity.RECOVERY]. */
        val WORKOUT_RECOVERY: Duration = WorkoutActivity.RECOVERY
    }
}
