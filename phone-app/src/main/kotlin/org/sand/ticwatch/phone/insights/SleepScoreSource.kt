package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.SampleDeriver
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import java.time.Duration
import java.time.Instant

/** Scores one sleep session from the history plus a push that has not been stored yet. */
fun interface SleepScoreSource {
    suspend fun score(night: SleepSession, push: List<SensorSample>): SleepScore
}

/**
 * Gathers [SleepScoreInputs]: stages and the night's ODI from raw rows (+ [push]), heart rate from the
 * 5-minute rollups (the night's HR arrived in earlier pushes; this push's readings are added on top) -
 * the night's mean against the mean of the [daytimeSpan] before it.
 */
class HistorySleepScoreSource(
    private val reader: SampleWindowReader,
    private val queries: SensorHistoryQueries,
    private val calculator: SleepScoreCalculator,
    private val daytimeSpan: Duration = Duration.ofHours(12),
) : SleepScoreSource {

    override suspend fun score(night: SleepSession, push: List<SensorSample>): SleepScore {
        val samples = reader.samples(NIGHT_TYPES, night.start, night.end, push)
        val stages = samples.filter { it.type == SensorType.SLEEP_STAGE && it.endedAt != null }
        val stageDurations = stages.groupBy { SleepStageType.fromCode(it.value.toInt()) }
            .filterKeys { it != null }
            .map { (type, list) -> checkNotNull(type) to list.fold(Duration.ZERO) { sum, stage -> sum + clipped(stage, night) } }
            .toMap()
        val awakenings = stages.count {
            it.value.toInt() == SleepStageType.AWAKE.code && it.recordedAt.isAfter(night.start) && checkNotNull(it.endedAt).isBefore(night.end)
        }
        return calculator.score(
            SleepScoreInputs(
                inBed = Duration.between(night.start, night.end),
                stageDurations = stageDurations,
                awakenings = awakenings,
                sleepHeartRateBpm = meanHeartRate(night.start, night.end, push),
                daytimeHeartRateBpm = meanHeartRate(night.start.minus(daytimeSpan), night.start, push),
                desaturationIndexPerHour = samples.lastOrNull { it.type == SensorType.OXYGEN_DESATURATION_INDEX }?.value,
            ),
        )
    }

    private fun clipped(stage: SensorSample, night: SleepSession): Duration {
        val start = maxOf(stage.recordedAt, night.start)
        val end = minOf(checkNotNull(stage.endedAt), night.end)
        return if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
    }

    private suspend fun meanHeartRate(from: Instant, to: Instant, push: List<SensorSample>): Double? {
        val stored = queries.summary(SensorType.HEART_RATE, RollupResolution.FIVE_MINUTES, from, to)
        val pushed = push.filter { it.type == SensorType.HEART_RATE && it.value > 0 && !it.recordedAt.isBefore(from) && it.recordedAt.isBefore(to) }
        val count = (stored?.count ?: 0L) + pushed.size
        if (count == 0L) return null
        return ((stored?.sum ?: 0.0) + pushed.sumOf { it.value }) / count
    }

    private companion object {
        val NIGHT_TYPES = setOf(SensorType.SLEEP_STAGE, SensorType.OXYGEN_DESATURATION_INDEX)
    }
}

/**
 * One [SensorType.SLEEP_SCORE] sample per finished night in a push, spanning the session. Must run after
 * the breathing summary, whose ODI for the night it reads from the push.
 */
class SleepScoreDeriver(private val source: SleepScoreSource) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> =
        samples.filter { it.type == SensorType.SLEEP && it.endedAt != null }.map { sleep ->
            val night = SleepSession(sleep.recordedAt, checkNotNull(sleep.endedAt))
            SensorSample(SensorType.SLEEP_SCORE, source.score(night, samples).score.toDouble(), night.start, night.end)
        }
}
