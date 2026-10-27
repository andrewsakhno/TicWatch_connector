package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.HeartRateZone
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Today's derived scores, shown on both dashboards; each null/empty until its data exists. */
data class HealthScores(
    val date: LocalDate,
    val sleepScore: SleepScore?,
    val hrvStatus: HrvStatus,
    val trainingLoad: TrainingLoadBalance,
    val weeklyZoneMinutes: Map<HeartRateZone, Double>,
    val stress: DailyStressSummary?,
    val morningLight: MorningLightExposure,
)

fun interface HealthScoresSource {
    suspend fun load(): HealthScores
}

/** Sleep sessions of a range, paged. */
internal suspend fun SensorHistoryQueries.allSleepSessions(from: Instant, to: Instant): List<SleepSession> = buildList {
    var afterStart: Instant? = null
    while (true) {
        val page = sleepSessions(from, to, afterStart, SLEEP_PAGE)
        addAll(page)
        if (page.size < SLEEP_PAGE) break
        afterStart = page.last().start
    }
}

private const val SLEEP_PAGE = 200

/** Nightly mean RMSSD per sleep session of at least [minNight], from the 5-minute rollups (kept 90 days). */
class NightlyRmssdHistory(
    private val queries: SensorHistoryQueries,
    private val zone: ZoneId,
    private val minNight: Duration = Duration.ofHours(3),
) {
    suspend fun nights(from: LocalDate, to: LocalDate): List<NightlyValue> =
        queries.allSleepSessions(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
            .filter { Duration.between(it.start, it.end) >= minNight }
            .mapNotNull { night ->
                queries.summary(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, night.start, night.end)
                    ?.let { NightlyValue(night.end.atZone(zone).toLocalDate(), it.average) }
            }
}

/** HRV status of every day in [from]..[to], reading the nights once. */
class HrvStatusHistory(
    private val nights: NightlyRmssdHistory,
    private val classifier: HrvStatusClassifier,
    private val lookbackDays: Long = BASELINE_LOOKBACK_DAYS,
) {
    suspend fun statuses(from: LocalDate, to: LocalDate): List<HrvStatus> {
        val values = nights.nights(from.minusDays(lookbackDays), to)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { classifier.classify(it, values) }.toList()
    }

    private companion object {
        const val BASELINE_LOOKBACK_DAYS = 28L
    }
}

/** Daily cardio load and zone minutes from the day rollups of the workout-derived samples (kept forever). */
class TrainingLoadHistory(
    private val queries: SensorHistoryQueries,
    private val calculator: TrainingLoadBalanceCalculator,
    private val zone: ZoneId,
    private val lookbackDays: Long = CHRONIC_LOOKBACK_DAYS,
) {
    suspend fun dailyLoads(from: LocalDate, to: LocalDate): Map<LocalDate, Double> = daily(SensorType.TRAINING_LOAD, from, to)

    suspend fun balances(from: LocalDate, to: LocalDate): List<TrainingLoadBalance> {
        val loads = dailyLoads(from.minusDays(lookbackDays), to)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { calculator.balance(it, loads) }.toList()
    }

    /** Minutes per zone summed over [from]..[to]; zones never reached are left out. */
    suspend fun zoneMinutes(from: LocalDate, to: LocalDate): Map<HeartRateZone, Double> =
        HeartRateZone.entries.associateWith { daily(it.sensorType, from, to).values.sum() }.filterValues { it > 0 }

    suspend fun dailyZoneMinutes(from: LocalDate, to: LocalDate): Map<LocalDate, Map<HeartRateZone, Double>> {
        val perZone = HeartRateZone.entries.associateWith { daily(it.sensorType, from, to) }
        return perZone.values.flatMap { it.keys }.toSortedSet().associateWith { date ->
            perZone.mapValues { (_, days) -> days[date] ?: 0.0 }.filterValues { it > 0 }
        }
    }

    private suspend fun daily(type: SensorType, from: LocalDate, to: LocalDate): Map<LocalDate, Double> =
        queries.rollups(type, RollupResolution.DAY, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
            .associate { it.bucketStart.atZone(zone).toLocalDate() to it.sum }

    private companion object {
        const val CHRONIC_LOOKBACK_DAYS = 27L
    }
}

/** A day's stress shares from the 5-minute RMSSD rollups, without sleep and walking buckets. */
class DailyStressHistory(
    private val queries: SensorHistoryQueries,
    private val calculator: DailyStressCalculator,
    private val zone: ZoneId,
) {
    suspend fun summary(date: LocalDate): DailyStressSummary? {
        val from = date.atStartOfDay(zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        val readings = queries.rollups(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, from, to).map { TrendPoint(it.bucketStart, it.average) }
        if (readings.isEmpty()) return null
        val sleep = queries.allSleepSessions(from.minus(MAX_NIGHT), to).map { TimeSpan(it.start, it.end) }
        val walking = queries.rollups(SensorType.STEPS_CADENCE, RollupResolution.FIVE_MINUTES, from, to)
            .map { TimeSpan(it.bucketStart, it.bucketStart.plus(RollupResolution.FIVE_MINUTES.bucket)) }
        return calculator.summarize(readings, sleep + walking)
    }

    private companion object {
        val MAX_NIGHT: Duration = Duration.ofHours(16)
    }
}

/** A day's morning bright-light time from the 5-minute light rollups. */
class MorningLightHistory(private val queries: SensorHistoryQueries, private val calculator: CircadianLightExposureCalculator) {
    suspend fun exposure(date: LocalDate): MorningLightExposure {
        val readings = queries.rollups(SensorType.AMBIENT_LIGHT, RollupResolution.FIVE_MINUTES, calculator.readFrom(date), calculator.readTo(date))
            .map { TrendPoint(it.bucketStart, it.average) }
        return calculator.exposure(date, readings)
    }
}

/** Composes today's [HealthScores]; last night is the latest session ending within [sleepLookback]. */
class HistoryHealthScoresSource(
    private val queries: SensorHistoryQueries,
    private val sleepScores: SleepScoreSource,
    private val hrv: HrvStatusHistory,
    private val training: TrainingLoadHistory,
    private val stress: DailyStressHistory,
    private val light: MorningLightHistory,
    private val clock: Clock,
    private val zone: ZoneId,
    private val sleepLookback: Duration = Duration.ofHours(36),
) : HealthScoresSource {

    override suspend fun load(): HealthScores {
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()
        val lastNight = queries.allSleepSessions(now.minus(sleepLookback), now).lastOrNull()
        return HealthScores(
            date = today,
            sleepScore = lastNight?.let { sleepScores.score(it, emptyList()) },
            hrvStatus = hrv.statuses(today, today).single(),
            trainingLoad = training.balances(today, today).single(),
            weeklyZoneMinutes = training.zoneMinutes(today.minusDays(WEEK_DAYS - 1), today),
            stress = stress.summary(today),
            morningLight = light.exposure(today),
        )
    }

    private companion object {
        const val WEEK_DAYS = 7L
    }
}
