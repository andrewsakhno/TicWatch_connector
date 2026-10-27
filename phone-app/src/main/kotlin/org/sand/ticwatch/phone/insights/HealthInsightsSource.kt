package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SkinBaselineSource
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.SleepStageQueries
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Read side of the Dashboard's "Metrics" tab. */
fun interface HealthInsightsSource {
    suspend fun load(): HealthInsights
}

/**
 * Builds [HealthInsights] from the rollups (so it never scans raw rows) and the long-lived sleep tables.
 * "Current" values are the latest 5-minute bucket of the last day; baselines are day buckets of the
 * [BASELINE_DAYS] before the night.
 */
class HistoryHealthInsightsSource(
    private val queries: SensorHistoryQueries,
    private val stages: SleepStageQueries,
    private val skinBaselines: SkinBaselineSource,
    private val scorer: ReadinessScorer,
    private val clock: Clock,
    private val zone: ZoneId,
) : HealthInsightsSource {

    override suspend fun load(): HealthInsights {
        val now = clock.instant()
        val night = lastNight(now)
        return HealthInsights(
            readiness = night?.let { readiness(it, now) },
            stress = stress(now),
            sleep = night?.let { sleep(it) },
            barometer = barometer(now),
            light = light(now),
            trends = InsightTrends(
                readiness = trend(SensorType.HRV_RMSSD, RollupResolution.DAY, now.minus(BASELINE_SPAN), now),
                stress = trend(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, now.minus(RECENT_SPAN), now),
                sleep = night?.let { trend(SensorType.SPO2, RollupResolution.FIVE_MINUTES, it.start, it.end) }
                    ?: Trend(emptyList(), now.minus(RECENT_SPAN), now),
                barometer = trend(SensorType.BAROMETRIC_PRESSURE, RollupResolution.FIVE_MINUTES, now.minus(RECENT_SPAN), now),
                light = trend(SensorType.AMBIENT_LIGHT, RollupResolution.FIVE_MINUTES, now.minus(RECENT_SPAN), now),
            ),
        )
    }

    private suspend fun trend(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): Trend =
        Trend(queries.rollups(type, resolution, from, to).map { TrendPoint(it.bucketStart, it.average) }, from, to)

    private suspend fun lastNight(now: Instant): SleepSession? =
        queries.sleepSessions(now.minus(SLEEP_LOOKBACK), now, afterStart = null, limit = SLEEP_PAGE).lastOrNull()

    private suspend fun readiness(night: SleepSession, now: Instant): Readiness? {
        val baselineFrom = RollupResolution.DAY.bucketStart(night.start.minus(BASELINE_SPAN), zone)
        val baselineTo = RollupResolution.DAY.bucketStart(night.start, zone)
        val nightlySkin = average(SensorType.SKIN_TEMPERATURE, RollupResolution.FIVE_MINUTES, night.start, night.end)
        val skinBaseline = skinBaselines.nightlyBaseline(night.start)
        return scorer.score(
            ReadinessInputs(
                nightlyRmssdMs = average(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, night.start, night.end),
                baselineRmssdMs = average(SensorType.HRV_RMSSD, RollupResolution.DAY, baselineFrom, baselineTo),
                restingHeartRateBpm = latest(SensorType.RESTING_HEART_RATE, RollupResolution.DAY, now.minus(SLEEP_LOOKBACK), now),
                baselineRestingHeartRateBpm =
                    average(SensorType.RESTING_HEART_RATE, RollupResolution.DAY, baselineFrom, baselineTo),
                skinTemperatureDeltaCelsius = if (nightlySkin != null && skinBaseline != null) nightlySkin - skinBaseline else null,
            ),
        )
    }

    private suspend fun stress(now: Instant): Stress? {
        val point = queries.rollups(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, now.minus(RECENT_SPAN), now)
            .lastOrNull() ?: return null
        return Stress(point.average, StressLevel.of(point.average), point.bucketStart)
    }

    private suspend fun sleep(night: SleepSession): SleepInsight {
        val stageDurations = stages.sleepStages(night.start, night.end)
            .groupBy { it.type }
            .mapValues { (_, list) -> list.fold(Duration.ZERO) { sum, stage -> sum + Duration.between(stage.start, stage.end) } }
        // The breathing summaries are one sample per night, recorded at the session start.
        return SleepInsight(
            start = night.start,
            end = night.end,
            stageDurations = stageDurations,
            desaturationIndexPerHour =
                average(SensorType.OXYGEN_DESATURATION_INDEX, RollupResolution.FIVE_MINUTES, night.start, night.end),
            snoringMinutes = average(SensorType.SNORING_DURATION, RollupResolution.FIVE_MINUTES, night.start, night.end),
        )
    }

    private suspend fun barometer(now: Instant): Barometer? {
        val current = latest(SensorType.BAROMETRIC_PRESSURE, RollupResolution.FIVE_MINUTES, now.minus(RECENT_SPAN), now)
            ?: return null
        val hours = queries.rollups(SensorType.BAROMETRIC_PRESSURE, RollupResolution.HOUR, now.minus(RECENT_SPAN), now)
        val change = if (hours.size >= MIN_TREND_BUCKETS) hours.last().average - hours.first().average else null
        return Barometer(current, change, change?.let(PressureTrend::of))
    }

    private suspend fun light(now: Instant): Light? {
        val point = queries.rollups(SensorType.AMBIENT_LIGHT, RollupResolution.FIVE_MINUTES, now.minus(RECENT_SPAN), now)
            .lastOrNull() ?: return null
        return Light(point.average, LightContext.of(point.average), point.bucketStart)
    }

    private suspend fun average(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): Double? =
        queries.summary(type, resolution, from, to)?.average

    private suspend fun latest(type: SensorType, resolution: RollupResolution, from: Instant, to: Instant): Double? =
        queries.rollups(type, resolution, from, to).lastOrNull()?.average

    private companion object {
        const val BASELINE_DAYS = 14L
        const val SLEEP_PAGE = 256
        const val MIN_TREND_BUCKETS = 2
        val BASELINE_SPAN: Duration = Duration.ofDays(BASELINE_DAYS)
        val SLEEP_LOOKBACK: Duration = Duration.ofDays(7)
        val RECENT_SPAN: Duration = Duration.ofDays(1)
    }
}
