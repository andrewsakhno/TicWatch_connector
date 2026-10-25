package org.sand.ticwatch.phone.health

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Decides whether a wrist skin-temperature reading was taken at rest, using the other samples
 * around it as context (Strategy, OCP: a new disturbance check is a new implementation registered
 * in the composition root). Activity cools the wrist by sweat evaporation and airflow while the
 * core warms, so skin readings taken during or shortly after it say nothing reliable about the
 * core. A qualifier without enough context to judge accepts the reading.
 */
fun interface SkinReadingQualifier {
    fun accepts(skin: SensorSample, context: List<SensorSample>): Boolean
}

/** Half-open time span [start, end). */
internal data class TimeSpan(val start: Instant, val end: Instant) {
    val duration: Duration get() = Duration.between(start, end)

    fun overlap(other: TimeSpan): Duration {
        val from = maxOf(start, other.start)
        val to = minOf(end, other.end)
        return if (from < to) Duration.between(from, to) else Duration.ZERO
    }

    operator fun contains(at: Instant): Boolean = !at.isBefore(start) && at.isBefore(end)

    companion object {
        /** A sample's measurement window extended [lookback] into the past; the end is inclusive of the window end. */
        fun around(skin: SensorSample, lookback: Duration): TimeSpan =
            TimeSpan(skin.recordedAt.minus(lookback), (skin.endedAt ?: skin.recordedAt).plusMillis(1))

        fun of(sample: SensorSample): TimeSpan =
            TimeSpan(sample.recordedAt, maxOf(sample.endedAt ?: sample.recordedAt, sample.recordedAt.plusMillis(1)))
    }
}

/** Rejects readings overlapping an exercise session or its [recovery] tail. */
class ExerciseRecoveryQualifier(
    private val recovery: Duration = Duration.ofMinutes(30),
) : SkinReadingQualifier {

    override fun accepts(skin: SensorSample, context: List<SensorSample>): Boolean {
        val span = TimeSpan.around(skin, recovery)
        return context.none { it.type == SensorType.EXERCISE && TimeSpan.of(it).overlap(span) > Duration.ZERO }
    }
}

/**
 * Rejects readings when the step cadence over the window plus [lookback] exceeds
 * [maxStepsPerMinute]: walking, not just shifting around. Step intervals only partly inside
 * the span count proportionally.
 */
class StepCadenceQualifier(
    private val lookback: Duration = Duration.ofMinutes(10),
    private val maxStepsPerMinute: Double = 15.0,
) : SkinReadingQualifier {

    override fun accepts(skin: SensorSample, context: List<SensorSample>): Boolean {
        val span = TimeSpan.around(skin, lookback)
        val steps = context.filter { it.type == SensorType.STEPS }.sumOf { sample ->
            val interval = TimeSpan.of(sample)
            sample.value * interval.overlap(span).toMillis() / interval.duration.toMillis()
        }
        return steps / span.duration.toMillis() * MILLIS_PER_MINUTE <= maxStepsPerMinute
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}

/**
 * Resting heart rate a reading at [at] is judged against: the lowest resting HR of its day or the
 * day before (the current day's minimum is still high just after midnight), from the context's
 * RESTING_HEART_RATE samples or [restingMinimums].
 */
class RestingHeartRateBaseline(
    private val restingMinimums: DailyMinimumSource,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun bpm(at: Instant, context: List<SensorSample>): Double? {
        val day = at.atZone(zoneId).toLocalDate()
        val days = setOf(day, day.minusDays(1))
        val fromContext = context
            .filter { it.type == SensorType.RESTING_HEART_RATE && it.recordedAt.atZone(zoneId).toLocalDate() in days }
            .map { it.value }
        return (fromContext + days.mapNotNull(restingMinimums::get)).minOrNull()
    }
}

/** Mean heart rate over a skin reading's window plus [lookback], or null with fewer than [MIN_HEART_RATE_SAMPLES] readings. */
internal fun meanHeartRate(skin: SensorSample, context: List<SensorSample>, lookback: Duration): Double? {
    val span = TimeSpan.around(skin, lookback)
    val bpm = context.filter { it.type == SensorType.HEART_RATE && it.recordedAt in span }.map { it.value }
    return if (bpm.size < MIN_HEART_RATE_SAMPLES) null else bpm.average()
}

/** HR arrives about once a second; fewer readings than this is too little to judge by. */
private const val MIN_HEART_RATE_SAMPLES = 30

/**
 * Rejects readings when the mean heart rate over the window plus [lookback] exceeds the
 * [restingBaseline] by more than [maxElevationRatio]; without a baseline, [fallbackMaxBpm] is
 * the limit.
 */
class HeartRateElevationQualifier(
    private val restingBaseline: RestingHeartRateBaseline,
    private val lookback: Duration = Duration.ofMinutes(5),
    private val maxElevationRatio: Double = 1.3,
    private val fallbackMaxBpm: Double = 95.0,
) : SkinReadingQualifier {

    override fun accepts(skin: SensorSample, context: List<SensorSample>): Boolean {
        val bpm = meanHeartRate(skin, context, lookback) ?: return true
        val limit = restingBaseline.bpm(TimeSpan.around(skin, lookback).end, context)?.times(maxElevationRatio) ?: fallbackMaxBpm
        return bpm <= limit
    }
}
