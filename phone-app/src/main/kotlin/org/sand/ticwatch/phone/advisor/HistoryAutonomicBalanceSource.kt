package org.sand.ticwatch.phone.advisor

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.sand.ticwatch.phone.deviation.DeviationDirection
import org.sand.ticwatch.phone.deviation.RecentDeviationEpisodes
import org.sand.ticwatch.phone.deviation.RestState
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.insights.CircadianNorm

/**
 * Gathers [AutonomicBalanceInputs] from the rollups and the deviation episodes. Current heart rate and RMSSD are short
 * windows ([HEART_RATE_SPAN], [RMSSD_SPAN]) so the tail shows while it lasts; stress events are looked for over the
 * longest window the calculator can open. The sleep score and fatigue index come from the readiness snapshot.
 */
class HistoryAutonomicBalanceSource(
    private val queries: SensorHistoryQueries,
    private val norm: CircadianNorm,
    private val episodes: RecentDeviationEpisodes,
    private val dropDetector: HeartRateDropDetector,
    private val calculator: AutonomicBalanceCalculator,
    private val zone: ZoneId,
) : AutonomicBalanceSource {

    override suspend fun current(readiness: CardioReadinessInputs): AutonomicBalance? {
        val now = readiness.at
        val hour = now.atZone(zone).hour
        val restingHeartRate = queries.rollups(SensorType.RESTING_HEART_RATE, RollupResolution.DAY, now.minus(RESTING_LOOKBACK), now)
            .lastOrNull()?.average
        return calculator.calculate(
            AutonomicBalanceInputs(
                at = now,
                lastStressEnd = lastStressEnd(now, restingHeartRate ?: readiness.heartRateNormBpm),
                rmssdMs = queries.summary(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, now.minus(RMSSD_SPAN), now)?.average,
                rmssdBaselineMs = norm.byHour(SensorType.HRV_RMSSD, now)[hour],
                heartRateBpm = queries.summary(SensorType.HEART_RATE, RollupResolution.FIVE_MINUTES, now.minus(HEART_RATE_SPAN), now)?.average,
                restingHeartRateBpm = restingHeartRate,
                sleepScore = readiness.sleepScore,
                fatiguePercent = readiness.fatiguePercent,
            ),
        )
    }

    /** The later of the last resting tachycardia episode's end and the last sharp heart rate fall at rest. */
    private suspend fun lastStressEnd(now: Instant, baselineBpm: Double?): Pair<PostStressTrigger, Instant>? {
        val since = now.minus(AutonomicBalanceCalculator.MAX_WINDOW)
        val episode = episodes.lastEnded(DeviationDirection.HIGH, RestState.AWAKE_REST, since)
            ?.let { PostStressTrigger.RESTING_TACHYCARDIA to it.end }
        val drop = baselineBpm?.let { baseline ->
            dropDetector.lastDropEnd(restingBuckets(since, now), baseline)?.let { PostStressTrigger.HEART_RATE_DROP to it }
        }
        return listOfNotNull(episode, drop).maxByOrNull { it.second }
    }

    private suspend fun restingBuckets(from: Instant, to: Instant): List<RestingHeartRateBucket> {
        val steps = queries.rollups(SensorType.STEPS, RollupResolution.FIVE_MINUTES, from, to).associate { it.bucketStart to it.sum }
        return queries.rollups(SensorType.HEART_RATE, RollupResolution.FIVE_MINUTES, from, to)
            .map { RestingHeartRateBucket(it.bucketStart, it.average, steps[it.bucketStart] ?: 0.0) }
    }

    private companion object {
        val HEART_RATE_SPAN: Duration = Duration.ofMinutes(15)
        val RMSSD_SPAN: Duration = Duration.ofHours(1)
        val RESTING_LOOKBACK: Duration = Duration.ofHours(36)
    }
}
