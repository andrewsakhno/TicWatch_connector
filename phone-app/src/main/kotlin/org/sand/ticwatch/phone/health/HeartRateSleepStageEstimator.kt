package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.history.SleepStage
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/**
 * Fallback sleep staging from heart rate and steps, per [epoch], relative to the night's own spread:
 * steps or a heart rate near the night's top mean awake; the lowest, steadiest heart rate early in the
 * night is deep sleep; a raised, variable heart rate after the first cycle is REM; the rest is light.
 * Single-epoch blips are smoothed out. Coarser than the vendor sensor - it only fills nights it missed.
 */
class HeartRateSleepStageEstimator(
    private val reader: SampleWindowReader,
    private val epoch: Duration = Duration.ofMinutes(5),
    private val minReadings: Int = 3,
    private val awakeSteps: Double = 20.0,
    private val awakeMarginBpm: Double = 8.0,
    private val firstCycle: Duration = Duration.ofMinutes(90),
    private val deepSleepShare: Double = 0.6,
) : SleepStageEstimator {

    private class EpochStats(val start: Instant, val meanBpm: Double?, val sdBpm: Double?, val steps: Double)

    override suspend fun estimate(start: Instant, end: Instant): List<SleepStage> {
        val samples = reader.samples(TYPES, start, end, emptyList())
        val epochs = generateSequence(start) { it.plus(epoch) }.takeWhile { it.isBefore(end) }.map { epochStart ->
            val epochEnd = minOf(epochStart.plus(epoch), end)
            val inEpoch = samples.filter { !it.recordedAt.isBefore(epochStart) && it.recordedAt.isBefore(epochEnd) }
            val bpm = inEpoch.filter { it.type == SensorType.HEART_RATE && it.value > 0 }.map { it.value }
            EpochStats(
                start = epochStart,
                meanBpm = bpm.takeIf { it.size >= minReadings }?.average(),
                sdBpm = bpm.takeIf { it.size >= minReadings }?.let(::standardDeviation),
                steps = inEpoch.filter { it.type == SensorType.STEPS }.sumOf { it.value },
            )
        }.toList()
        val means = epochs.mapNotNull { it.meanBpm }.sorted()
        if (means.size < MIN_SCORED_EPOCHS) return emptyList()
        val deviations = epochs.mapNotNull { it.sdBpm }.sorted()
        val thresholds = Thresholds(
            low = means.percentile(LOW_PERCENTILE),
            median = means.percentile(MEDIAN_PERCENTILE),
            high = means.percentile(HIGH_PERCENTILE),
            medianSd = deviations.percentile(MEDIAN_PERCENTILE),
        )
        val night = Duration.between(start, end)
        val raw = epochs.map { classify(it, thresholds, Duration.between(start, it.start), night) }
        val smoothed = raw.indices.map { i ->
            val previous = raw.getOrNull(i - 1)
            if (previous != null && previous == raw.getOrNull(i + 1) && previous != raw[i]) previous else raw[i]
        }
        return merge(epochs.map { it.start }, smoothed, end, start)
    }

    private class Thresholds(val low: Double, val median: Double, val high: Double, val medianSd: Double)

    private fun classify(stats: EpochStats, t: Thresholds, elapsed: Duration, night: Duration): SleepStageType {
        if (stats.steps >= awakeSteps) return SleepStageType.AWAKE
        val mean = stats.meanBpm ?: return SleepStageType.LIGHT
        val sd = stats.sdBpm ?: 0.0
        return when {
            mean >= t.high && mean >= t.median + awakeMarginBpm -> SleepStageType.AWAKE
            mean <= t.low && sd <= t.medianSd && elapsed.toMillis() < night.toMillis() * deepSleepShare -> SleepStageType.DEEP
            mean >= t.median && sd >= t.medianSd && elapsed >= firstCycle -> SleepStageType.REM
            else -> SleepStageType.LIGHT
        }
    }

    private fun merge(starts: List<Instant>, types: List<SleepStageType>, end: Instant, session: Instant): List<SleepStage> {
        val stages = mutableListOf<SleepStage>()
        var runStart = 0
        for (i in types.indices) {
            if (i + 1 < types.size && types[i + 1] == types[runStart]) continue
            val runEnd = starts.getOrNull(i + 1) ?: end
            stages += SleepStage(starts[runStart], runEnd, types[runStart], session)
            runStart = i + 1
        }
        return stages
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun List<Double>.percentile(fraction: Double): Double = this[((size - 1) * fraction).toInt()]

    private companion object {
        val TYPES = setOf(SensorType.HEART_RATE, SensorType.STEPS)
        const val MIN_SCORED_EPOCHS = 6
        const val LOW_PERCENTILE = 0.25
        const val MEDIAN_PERCENTILE = 0.5
        const val HIGH_PERCENTILE = 0.9
    }
}
