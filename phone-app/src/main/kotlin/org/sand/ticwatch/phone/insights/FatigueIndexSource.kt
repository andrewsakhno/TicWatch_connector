package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.sleep.VagalRecoverySource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The current index from the history plus a push that has not been stored yet; null without enough data. */
fun interface FatigueIndexSource {
    suspend fun current(push: List<SensorSample>): FatigueIndex?
}

/** Mean of a sensor's hourly buckets per local hour over the [days] before today: the wearer's daily rhythm. */
class CircadianNorm(
    private val queries: SensorHistoryQueries,
    private val zone: ZoneId,
    private val days: Long = 14,
) {
    suspend fun byHour(type: SensorType, now: Instant): Map<Int, Double> {
        val todayStart = RollupResolution.DAY.bucketStart(now, zone)
        return queries.rollups(type, RollupResolution.HOUR, todayStart.minus(Duration.ofDays(days)), todayStart)
            .groupBy { it.bucketStart.atZone(zone).hour }
            .mapValues { (_, buckets) -> buckets.map { it.average }.average() }
    }
}

/**
 * Gathers [FatigueInputs] from the rollups. Current RMSSD = the last [RMSSD_SPAN]; resting heart rate = the lowest
 * 5-minute mean of the last [RESTING_SPAN] among buckets of at most [MAX_RESTING_STEPS] steps, null while walking
 * the whole time. A night in [push] counts through its sleep score; its vagal recovery follows once it is stored.
 */
class HistoryFatigueIndexSource(
    private val queries: SensorHistoryQueries,
    private val norm: CircadianNorm,
    private val recovery: VagalRecoverySource,
    private val calculator: FatigueIndexCalculator,
    private val clock: Clock,
    private val zone: ZoneId,
) : FatigueIndexSource {

    override suspend fun current(push: List<SensorSample>): FatigueIndex? {
        val now = clock.instant()
        val hour = now.atZone(zone).hour
        return calculator.calculate(
            FatigueInputs(
                steps24h = window(SensorType.STEPS, RollupResolution.HOUR, now.minus(LOAD_SPAN), now, push)?.sum,
                trainingLoad24h = window(SensorType.TRAINING_LOAD, RollupResolution.FIVE_MINUTES, now.minus(LOAD_SPAN), now, push)?.sum,
                rmssdMs = window(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, now.minus(RMSSD_SPAN), now, push)?.mean,
                rmssdBaselineMs = norm.byHour(SensorType.HRV_RMSSD, now)[hour],
                restingHeartRateBpm = restingHeartRate(now, push),
                heartRateNormBpm = norm.byHour(SensorType.HEART_RATE, now)[hour],
                sleepScore = latestSleepScore(now, push),
                vagalRecoveryRatio = recovery.lastNightRecovery()?.ratio,
            ),
        )
    }

    private suspend fun latestSleepScore(now: Instant, push: List<SensorSample>): Double? =
        push.lastOrNull { it.type == SensorType.SLEEP_SCORE }?.value
            ?: queries.rollups(SensorType.SLEEP_SCORE, RollupResolution.FIVE_MINUTES, now.minus(SLEEP_LOOKBACK), now).lastOrNull()?.average

    private suspend fun restingHeartRate(now: Instant, push: List<SensorSample>): Double? {
        val from = now.minus(RESTING_SPAN)
        val heartRate = buckets(SensorType.HEART_RATE, from, now, push)
        val steps = buckets(SensorType.STEPS, from, now, push)
        return heartRate.filterKeys { bucket -> (steps[bucket]?.sum ?: 0.0) <= MAX_RESTING_STEPS }.values.minOfOrNull { it.mean }
    }

    private suspend fun window(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant, push: List<SensorSample>): Totals? {
        val stored = queries.summary(type, resolution, from, to)?.let { Totals(it.sum, it.count) } ?: Totals.NONE
        val total = stored + pushed(type, from, to, push).toTotals()
        return total.takeIf { it.count > 0 }
    }

    /** 5-minute buckets of [type], stored rollups and pushed readings together. */
    private suspend fun buckets(type: SensorType, from: Instant, to: Instant, push: List<SensorSample>): Map<Instant, Totals> {
        val stored = queries.rollups(type, RollupResolution.FIVE_MINUTES, from, to).associate { it.bucketStart to Totals(it.sum, it.count) }
        val pushed = pushed(type, from, to, push)
            .groupBy { RollupResolution.FIVE_MINUTES.bucketStart(it.recordedAt, zone) }
            .mapValues { (_, readings) -> readings.toTotals() }
        return (stored.keys + pushed.keys).associateWith { (stored[it] ?: Totals.NONE) + (pushed[it] ?: Totals.NONE) }
    }

    private fun pushed(type: SensorType, from: Instant, to: Instant, push: List<SensorSample>): List<SensorSample> =
        push.filter { it.type == type && it.value > 0 && !it.recordedAt.isBefore(from) && !it.recordedAt.isAfter(to) }

    private fun List<SensorSample>.toTotals() = Totals(sumOf { it.value }, size.toLong())

    private data class Totals(val sum: Double, val count: Long) {
        val mean: Double get() = sum / count

        operator fun plus(other: Totals) = Totals(sum + other.sum, count + other.count)

        companion object {
            val NONE = Totals(0.0, 0L)
        }
    }

    private companion object {
        val LOAD_SPAN: Duration = Duration.ofHours(24)
        val RMSSD_SPAN: Duration = Duration.ofHours(2)
        val RESTING_SPAN: Duration = Duration.ofHours(1)
        val SLEEP_LOOKBACK: Duration = Duration.ofHours(36)

        /** 40 steps/min over a 5-minute bucket, the walking threshold of the blood pressure estimate. */
        const val MAX_RESTING_STEPS = 200.0
    }
}
