package org.sand.ticwatch.phone.sleep

import androidx.annotation.StringRes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.health.medianOrNull
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.history.SleepStageQueries

/** Continuity of one night from its stages, between sleep onset and the final awakening. */
data class SleepContinuity(
    val onset: Instant,
    val finalAwakening: Instant,
    /** Wake After Sleep Onset: awake time between [onset] and [finalAwakening]. */
    val waso: Duration,
    /** Awake stages interrupting sleep (consecutive awake stages count once). */
    val awakenings: Int,
    val totalSleep: Duration,
    val longestSleepBout: Duration,
) {
    /** Longest uninterrupted sleep bout as a share of total sleep: 1 = one unbroken block. */
    val longestBoutRatio: Double get() = if (totalSleep.isZero) 0.0 else longestSleepBout.toMillis().toDouble() / totalSleep.toMillis()

    /** Sleep Fragmentation Index: 1 - [longestBoutRatio], 0 = unbroken, towards 1 = many short bouts. */
    val fragmentationIndex: Double get() = 1.0 - longestBoutRatio
}

/**
 * WASO, awakenings and the longest sleep bout. A gap between stages longer than [maxGap] (no stage data) ends the
 * current bout without counting as an awakening - missing data is not wakefulness.
 */
class SleepContinuityCalculator(private val maxGap: Duration = Duration.ofMinutes(5)) {

    fun calculate(stages: List<SleepStage>): SleepContinuity? {
        val sorted = stages.sortedBy { it.start }
        val asleep = sorted.filter { it.type != SleepStageType.AWAKE }
        if (asleep.isEmpty()) return null
        val onset = asleep.first().start
        val finalAwakening = asleep.maxOf { it.end }
        var waso = Duration.ZERO
        var totalSleep = Duration.ZERO
        var longest = Duration.ZERO
        var bout = Duration.ZERO
        var boutEnd: Instant? = null
        var awakenings = 0
        sorted.forEach { stage ->
            val start = maxOf(stage.start, onset)
            val end = minOf(stage.end, finalAwakening)
            if (!end.isAfter(start)) return@forEach
            val length = Duration.between(start, end)
            if (stage.type == SleepStageType.AWAKE) {
                waso = waso.plus(length)
                if (!bout.isZero) awakenings++
                longest = maxOf(longest, bout)
                bout = Duration.ZERO
                boutEnd = null
            } else {
                if (boutEnd?.let { Duration.between(it, start) > maxGap } == true) {
                    longest = maxOf(longest, bout)
                    bout = Duration.ZERO
                }
                bout = bout.plus(length)
                totalSleep = totalSleep.plus(length)
                boutEnd = end
            }
        }
        return SleepContinuity(onset, finalAwakening, waso, awakenings, totalSleep, maxOf(longest, bout))
    }
}

/** Overall verdict on snoring and the heart, derived from the counts. */
enum class SnoreCardiacImpact(@StringRes val label: Int) {
    NO_SNORING(R.string.snore_impact_no_snoring),
    NOT_ASSESSED(R.string.snore_impact_not_assessed),
    BENIGN(R.string.snore_impact_benign),
    AUTONOMIC_STRESS(R.string.snore_impact_autonomic_stress);

    companion object {
        fun of(episodes: Int, assessed: Int, spikes: Int): SnoreCardiacImpact = when {
            episodes == 0 -> NO_SNORING
            assessed == 0 -> NOT_ASSESSED
            spikes > 0 -> AUTONOMIC_STRESS
            else -> BENIGN
        }
    }
}

/** Snore-Cardiac Arousal of one night: snoring epochs vs heart rate and RMSSD in the same epochs. */
data class SnoreCardiacArousal(
    /** 5-min epochs with snoring. */
    val episodes: Int,
    /** Snoring epochs that had heart-rate samples. */
    val assessedEpisodes: Int,
    /** Snoring epochs whose mean heart rate exceeded the nightly baseline by more than the spike threshold. */
    val cardiacSpikes: Int,
    val baselineHeartRateBpm: Double?,
    val snoringRmssdMs: Double?,
    val quietRmssdMs: Double?,
) {
    val impact: SnoreCardiacImpact get() = SnoreCardiacImpact.of(episodes, assessedEpisodes, cardiacSpikes)
}

/**
 * Compares each snoring epoch (SNORING fraction >= [minSnoreFraction]) with the nightly heart-rate baseline - the
 * median of the samples outside snoring epochs (all samples when there are none). A mean > baseline x
 * (1 + [spikeShare]) is an autonomic arousal; otherwise the snoring is a benign acoustic phenomenon.
 */
class SnoreCardiacArousalAnalyzer(
    private val minSnoreFraction: Double = 0.05,
    private val spikeShare: Double = 0.10,
    private val defaultEpoch: Duration = Duration.ofMinutes(5),
) {

    fun analyze(snoring: List<SensorSample>, heartRate: List<SensorSample>, rmssd: List<SensorSample>): SnoreCardiacArousal {
        val epochs = snoring.filter { it.value >= minSnoreFraction }.map { it.recordedAt to (it.endedAt ?: it.recordedAt.plus(defaultEpoch)) }
        fun inSnoring(at: Instant) = epochs.any { (start, end) -> !at.isBefore(start) && at.isBefore(end) }
        val quietHeartRate = heartRate.filterNot { inSnoring(it.recordedAt) }.ifEmpty { heartRate }
        val baseline = quietHeartRate.map { it.value }.medianOrNull()
        var assessed = 0
        var spikes = 0
        epochs.forEach { (start, end) ->
            val inEpoch = heartRate.filter { !it.recordedAt.isBefore(start) && it.recordedAt.isBefore(end) }
            if (inEpoch.isEmpty() || baseline == null) return@forEach
            assessed++
            if (inEpoch.map { it.value }.average() > baseline * (1.0 + spikeShare)) spikes++
        }
        val (snoringRmssd, quietRmssd) = rmssd.partition { inSnoring(it.recordedAt) }
        return SnoreCardiacArousal(
            episodes = epochs.size,
            assessedEpisodes = assessed,
            cardiacSpikes = spikes,
            baselineHeartRateBpm = baseline,
            snoringRmssdMs = snoringRmssd.meanOrNull(),
            quietRmssdMs = quietRmssd.meanOrNull(),
        )
    }
}

/** Vagal Recovery Ratio bands, descending; [minRatio] is where each starts. */
enum class VagalRecoveryStatus(@StringRes val label: Int, val minRatio: Double) {
    EXCELLENT(R.string.vagal_recovery_excellent, 1.5),
    MODERATE(R.string.vagal_recovery_moderate, 1.1),
    BALANCED(R.string.vagal_recovery_balanced, 1.0),
    HIDDEN_STRESS(R.string.vagal_recovery_hidden_stress, 0.0);

    companion object {
        fun of(ratio: Double): VagalRecoveryStatus = entries.first { ratio >= it.minRatio }
    }
}

/** Night RMSSD vs the preceding day's daytime RMSSD. */
data class VagalRecovery(val nightRmssdMs: Double, val dayRmssdMs: Double) {
    val ratio: Double get() = nightRmssdMs / dayRmssdMs
    val status: VagalRecoveryStatus get() = VagalRecoveryStatus.of(ratio)
}

class VagalRecoveryCalculator {
    /** Null when either mean is missing or the daytime mean is not positive. */
    fun calculate(nightRmssdMs: Double?, dayRmssdMs: Double?): VagalRecovery? {
        if (nightRmssdMs == null || dayRmssdMs == null || dayRmssdMs <= 0.0) return null
        return VagalRecovery(nightRmssdMs, dayRmssdMs)
    }
}

/**
 * Evening distal vasodilation (the body opening its sleep window): the first wrist skin temperature reading in
 * [onset - [searchWindow], onset] at least [minRise] °C above the lowest reading of the preceding [baselineWindow].
 */
class EveningVasodilationDetector(
    private val minRise: Double = 1.2,
    private val searchWindow: Duration = Duration.ofHours(2),
    private val baselineWindow: Duration = Duration.ofHours(1),
) {
    /** How far before the sleep onset the skin readings must reach. */
    val lookback: Duration get() = searchWindow.plus(baselineWindow)

    fun detect(skin: List<SensorSample>, sleepOnset: Instant): Instant? {
        val sorted = skin.sortedBy { it.recordedAt }
        val searchStart = sleepOnset.minus(searchWindow)
        return sorted.firstOrNull { reading ->
            val at = reading.recordedAt
            if (at.isBefore(searchStart) || at.isAfter(sleepOnset)) return@firstOrNull false
            val baseline = sorted.filter { it.recordedAt.isBefore(at) && !it.recordedAt.isBefore(at.minus(baselineWindow)) }
                .minOfOrNull { it.value } ?: return@firstOrNull false
            reading.value - baseline >= minRise
        }?.recordedAt
    }
}

/** Wrist screening level for sleep apnea; [minOdi] = ODI where each band starts (null for UNKNOWN). */
enum class ApneaRiskLevel(@StringRes val label: Int, val minOdi: Double?) {
    UNKNOWN(R.string.apnea_risk_unknown, null),
    LOW(R.string.apnea_risk_low, 0.0),
    MILD(R.string.apnea_risk_mild, 5.0),
    MODERATE(R.string.apnea_risk_moderate, 15.0),
    SEVERE(R.string.apnea_risk_severe, 30.0),
}

/**
 * ODI bands (<5 / 5-15 / 15-30 / >30 per hour); a night with at least [arousalSpikesForMild] snore-cardiac spikes is
 * raised to at least MILD, since arousals without desaturation are typical of upper-airway resistance.
 */
class ApneaRiskClassifier(private val arousalSpikesForMild: Int = 3) {

    fun classify(odiPerHour: Double?, snore: SnoreCardiacArousal): ApneaRiskLevel {
        val arousals = snore.cardiacSpikes >= arousalSpikesForMild
        val byOdi = odiPerHour?.let { odi -> ApneaRiskLevel.entries.last { level -> level.minOdi?.let { odi >= it } ?: false } }
        return when {
            byOdi == null -> if (arousals) ApneaRiskLevel.MILD else ApneaRiskLevel.UNKNOWN
            arousals && byOdi == ApneaRiskLevel.LOW -> ApneaRiskLevel.MILD
            else -> byOdi
        }
    }
}

/** Everything [SleepAdvancedAnalytics] needs for one night, already read from the history. */
data class SleepNightData(
    val session: SleepSession,
    val stages: List<SleepStage>,
    val snoring: List<SensorSample>,
    val heartRate: List<SensorSample>,
    val rmssd: List<SensorSample>,
    val eveningSkinTemperature: List<SensorSample>,
    val odiPerHour: Double?,
    val nightRmssdMs: Double?,
    val dayRmssdMs: Double?,
)

/** Advanced sleep analytics of one night; each part null (or an empty verdict) without its data. */
data class SleepAdvancedInsights(
    val session: SleepSession,
    val continuity: SleepContinuity?,
    val snore: SnoreCardiacArousal,
    val recovery: VagalRecovery?,
    val vasodilationAt: Instant?,
    val odiPerHour: Double?,
    val apneaRisk: ApneaRiskLevel,
)

/** Pure composition of the night calculators. */
class SleepAdvancedAnalytics(
    private val continuity: SleepContinuityCalculator,
    private val snore: SnoreCardiacArousalAnalyzer,
    private val recovery: VagalRecoveryCalculator,
    private val vasodilation: EveningVasodilationDetector,
    private val apnea: ApneaRiskClassifier,
) {
    val vasodilationLookback: Duration get() = vasodilation.lookback

    fun analyze(night: SleepNightData): SleepAdvancedInsights {
        val sleepContinuity = continuity.calculate(night.stages)
        val arousal = snore.analyze(night.snoring, night.heartRate, night.rmssd)
        return SleepAdvancedInsights(
            session = night.session,
            continuity = sleepContinuity,
            snore = arousal,
            recovery = recovery.calculate(night.nightRmssdMs, night.dayRmssdMs),
            vasodilationAt = vasodilation.detect(night.eveningSkinTemperature, sleepContinuity?.onset ?: night.session.start),
            odiPerHour = night.odiPerHour,
            apneaRisk = apnea.classify(night.odiPerHour, arousal),
        )
    }
}

/** The latest night's vagal recovery, for the Advisor badge (ISP: the screen needs nothing else). */
fun interface VagalRecoverySource {
    suspend fun lastNightRecovery(): VagalRecovery?
}

/**
 * Reads nights for [SleepAdvancedAnalytics]: stages from the `sleep_stage` table, per-epoch snoring / heart rate /
 * RMSSD and the evening skin temperature from raw rows (so as far back as the raw retention), night and daytime
 * RMSSD means from the 5-minute rollups. Daytime = [DAY_START_HOUR, DAY_END_HOUR) local on the evening's date,
 * capped at the session start.
 */
class SleepAdvancedInsightsReader(
    private val queries: SensorHistoryQueries,
    private val stages: SleepStageQueries,
    private val samples: SampleWindowReader,
    private val analytics: SleepAdvancedAnalytics,
    private val clock: Clock,
    private val zone: ZoneId,
) : VagalRecoverySource {

    suspend fun night(session: SleepSession): SleepAdvancedInsights {
        val nightStages = stages.sleepStages(session.start, session.end)
        val onset = nightStages.filter { it.type != SleepStageType.AWAKE }.minOfOrNull { it.start } ?: session.start
        val nightSamples = samples.samples(NIGHT_TYPES, session.start, session.end, emptyList())
        fun of(type: SensorType) = nightSamples.filter { it.type == type }
        val skin = samples.samples(SKIN_TYPES, onset.minus(analytics.vasodilationLookback), onset.plusMillis(1), emptyList())
        return analytics.analyze(
            SleepNightData(
                session = session,
                stages = nightStages,
                snoring = of(SensorType.SNORING),
                heartRate = of(SensorType.HEART_RATE).filter { it.source == SampleSource.WATCH },
                rmssd = of(SensorType.HRV_RMSSD),
                eveningSkinTemperature = skin,
                odiPerHour = of(SensorType.OXYGEN_DESATURATION_INDEX).lastOrNull()?.value,
                nightRmssdMs = rmssdMean(session.start, session.end),
                dayRmssdMs = daytimeWindow(session.start)?.let { (from, to) -> rmssdMean(from, to) },
            ),
        )
    }

    /** The longest session ending on local [date] (the wake-up day), null without one. */
    suspend fun nightEndingOn(date: LocalDate): SleepAdvancedInsights? {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        return sessions(dayStart.minus(MAX_SESSION), dayEnd)
            .filter { !it.end.isBefore(dayStart) && it.end.isBefore(dayEnd) }
            .maxByOrNull { Duration.between(it.start, it.end) }
            ?.let { night(it) }
    }

    /** The night ending on the day of the most recent session's end within [LATEST_LOOKBACK], null without one. */
    suspend fun latestNight(): SleepAdvancedInsights? {
        val now = clock.instant()
        val latest = sessions(now.minus(LATEST_LOOKBACK), now).maxByOrNull { it.end } ?: return null
        return nightEndingOn(latest.end.atZone(zone).toLocalDate())
    }

    /** Nights ending in [from, to), oldest first. */
    suspend fun nightsEndingIn(from: Instant, to: Instant): List<SleepAdvancedInsights> =
        sessions(from.minus(MAX_SESSION), to)
            .filter { !it.end.isBefore(from) && it.end.isBefore(to) && Duration.between(it.start, it.end) >= MIN_NIGHT }
            .map { night(it) }

    override suspend fun lastNightRecovery(): VagalRecovery? = latestNight()?.recovery

    private fun daytimeWindow(sessionStart: Instant): Pair<Instant, Instant>? {
        val evening = sessionStart.minus(EVENING_SHIFT).atZone(zone).toLocalDate()
        val from = evening.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant()
        val to = minOf(evening.atTime(DAY_END_HOUR, 0).atZone(zone).toInstant(), sessionStart)
        return (from to to).takeIf { to.isAfter(from) }
    }

    private suspend fun rmssdMean(from: Instant, to: Instant): Double? =
        queries.summary(SensorType.HRV_RMSSD, RollupResolution.FIVE_MINUTES, from, to)?.average

    private suspend fun sessions(from: Instant, to: Instant): List<SleepSession> = buildList {
        var afterStart: Instant? = null
        while (true) {
            val page = queries.sleepSessions(from, to, afterStart, PAGE_SIZE)
            addAll(page)
            if (page.size < PAGE_SIZE) break
            afterStart = page.last().start
        }
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val DAY_START_HOUR = 8
        const val DAY_END_HOUR = 21
        /** Maps a session starting after midnight to the previous evening's date. */
        val EVENING_SHIFT: Duration = Duration.ofHours(6)
        val MAX_SESSION: Duration = Duration.ofHours(16)
        val MIN_NIGHT: Duration = Duration.ofHours(3)
        val LATEST_LOOKBACK: Duration = Duration.ofDays(3)
        val NIGHT_TYPES = setOf(SensorType.SNORING, SensorType.HEART_RATE, SensorType.HRV_RMSSD, SensorType.OXYGEN_DESATURATION_INDEX)
        val SKIN_TYPES = setOf(SensorType.SKIN_TEMPERATURE)
    }
}

private fun List<SensorSample>.meanOrNull(): Double? = takeIf { it.isNotEmpty() }?.map { it.value }?.average()
