package org.sand.ticwatch.phone.report

import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.medianOrNull
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.medication.MedicationEffect
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Half-open time range [start, end). */
data class TimeRange(val start: Instant, val end: Instant) {
    operator fun contains(at: Instant): Boolean = !at.isBefore(start) && at.isBefore(end)
}

/** ABPM day/night split in local time: day [DAY_START, NIGHT_START), night [NIGHT_START, DAY_START of the next day). */
class DayNightWindow(private val zone: ZoneId) {

    fun isDaytime(at: Instant): Boolean {
        val time = at.atZone(zone).toLocalTime()
        return !time.isBefore(DAY_START) && time.isBefore(NIGHT_START)
    }

    /** Every night overlapping [from, to), oldest first. */
    fun nights(from: Instant, to: Instant): List<TimeRange> {
        val firstEvening = from.atZone(zone).toLocalDate().minusDays(1)
        val lastEvening = to.atZone(zone).toLocalDate()
        return generateSequence(firstEvening) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastEvening) }
            .map { date -> TimeRange(date.atTime(NIGHT_START).atZone(zone).toInstant(), date.plusDays(1).atTime(DAY_START).atZone(zone).toInstant()) }
            .filter { it.end.isAfter(from) && it.start.isBefore(to) }
            .toList()
    }

    companion object {
        val DAY_START: LocalTime = LocalTime.of(7, 0)
        val NIGHT_START: LocalTime = LocalTime.of(23, 0)
    }
}

/** 24 h / daytime / nighttime means of the readings (cuff readings and wrist estimates alike). */
class AmbulatoryPressureCalculator(private val window: DayNightWindow) {

    fun summarize(readings: List<BloodPressureReading>): AmbulatoryPressureSummary {
        val (daytime, nighttime) = readings.partition { window.isDaytime(it.measuredAt) }
        return AmbulatoryPressureSummary(
            overall = mean(readings),
            daytime = mean(daytime),
            nighttime = mean(nighttime),
            cuffReadings = readings.count { it.source == SampleSource.BLOOD_PRESSURE_CUFF },
            wristReadings = readings.count { it.source == SampleSource.WATCH },
        )
    }

    private fun mean(readings: List<BloodPressureReading>): PressureMean? =
        if (readings.isEmpty()) null else PressureMean(readings.map { it.systolic }.average(), readings.map { it.diastolic }.average(), readings.size)
}

/**
 * Heart rate part of the report from watch heart rates: night resting HR = lowest [BUCKET] mean of each night,
 * averaged over nights; morning surge = mean of the first [SURGE_SPAN] after waking (end of the night's sleep session,
 * else [DayNightWindow.DAY_START]) minus that night's resting HR; tachycardia = daytime runs above
 * [TACHYCARDIA_THRESHOLD] lasting >= [MIN_EPISODE] outside recorded workouts; onset = median over intakes.
 */
class HeartRateReportCalculator(private val window: DayNightWindow) {

    fun summarize(
        heartRates: List<SensorSample>,
        from: Instant,
        to: Instant,
        sleepSessions: List<SleepSession>,
        workouts: List<TimeRange>,
        effects: List<MedicationEffect>,
    ): HeartRateSummary {
        val nightly = window.nights(from, to).mapNotNull { night ->
            lowestBucketMean(heartRates.filter { it.recordedAt in night })?.let { night to it }
        }
        val surges = nightly.mapNotNull { (night, resting) ->
            val wake = wakeTime(night, sleepSessions)
            meanOrNull(heartRates.filter { it.recordedAt in TimeRange(wake, wake.plus(SURGE_SPAN)) })?.minus(resting)
        }
        val episodes = tachycardiaEpisodes(
            heartRates.filter { it.recordedAt in TimeRange(from, to) && window.isDaytime(it.recordedAt) && workouts.none { w -> it.recordedAt in w } },
        )
        val onsets = effects.mapNotNull { it.onset }
        return HeartRateSummary(
            nightRestingHeartRate = nightly.map { it.second }.average().takeUnless { it.isNaN() },
            nights = nightly.size,
            morningSurge = surges.average().takeUnless { it.isNaN() },
            tachycardiaEpisodes = episodes.size,
            tachycardiaDuration = episodes.fold(Duration.ZERO, Duration::plus),
            medicationOnset = onsets.map { it.seconds.toDouble() }.medianOrNull()?.let { Duration.ofSeconds(it.toLong()) },
            intakesWithOnset = onsets.size,
            intakes = effects.size,
        )
    }

    private fun lowestBucketMean(samples: List<SensorSample>): Double? =
        samples.groupBy { it.recordedAt.toEpochMilli() / BUCKET.toMillis() }.values
            .filter { it.size >= MIN_BUCKET_SAMPLES }
            .minOfOrNull { bucket -> bucket.map { it.value }.average() }

    private fun wakeTime(night: TimeRange, sessions: List<SleepSession>): Instant =
        sessions.filter { it.end in TimeRange(night.end.minus(WAKE_SEARCH_BEFORE), night.end.plus(WAKE_SEARCH_AFTER)) }
            .maxByOrNull { Duration.between(it.start, it.end) }
            ?.end
            ?: night.end

    private fun meanOrNull(samples: List<SensorSample>): Double? =
        if (samples.size < MIN_BUCKET_SAMPLES) null else samples.map { it.value }.average()

    /** Durations of runs of consecutive readings above the threshold (gaps <= [MAX_SAMPLE_GAP]). */
    private fun tachycardiaEpisodes(samples: List<SensorSample>): List<Duration> {
        val episodes = mutableListOf<Duration>()
        var runStart: Instant? = null
        var runEnd: Instant? = null
        fun close() {
            val start = runStart
            val end = runEnd
            if (start != null && end != null) Duration.between(start, end).takeIf { it >= MIN_EPISODE }?.let(episodes::add)
            runStart = null
            runEnd = null
        }
        samples.sortedBy { it.recordedAt }.forEach { sample ->
            val last = runEnd
            if (sample.value <= TACHYCARDIA_THRESHOLD) {
                close()
                return@forEach
            }
            if (last != null && Duration.between(last, sample.recordedAt) > MAX_SAMPLE_GAP) close()
            if (runStart == null) runStart = sample.recordedAt
            runEnd = sample.recordedAt
        }
        close()
        return episodes
    }

    companion object {
        const val TACHYCARDIA_THRESHOLD = 90.0
        val MIN_EPISODE: Duration = Duration.ofMinutes(2)
        private val MAX_SAMPLE_GAP = Duration.ofMinutes(5)
        private val BUCKET = Duration.ofMinutes(15)
        private const val MIN_BUCKET_SAMPLES = 3
        private val SURGE_SPAN = Duration.ofHours(1)
        private val WAKE_SEARCH_BEFORE = Duration.ofHours(4)
        private val WAKE_SEARCH_AFTER = Duration.ofHours(5)
    }
}

/** Pairs every cuff reading with the nearest wrist estimate within [MAX_GAP]. */
class CalibrationAccuracyCalculator {

    fun compare(readings: List<BloodPressureReading>): CalibrationAccuracy {
        val (cuff, wrist) = readings.partition { it.source == SampleSource.BLOOD_PRESSURE_CUFF }
        val estimates = wrist.filter { it.source == SampleSource.WATCH }
        val comparisons = cuff.mapNotNull { reference ->
            estimates.filter { Duration.between(reference.measuredAt, it.measuredAt).abs() <= MAX_GAP }
                .minByOrNull { Duration.between(reference.measuredAt, it.measuredAt).abs() }
                ?.let { CuffComparison(reference, it) }
        }
        return CalibrationAccuracy(cuff, comparisons)
    }

    companion object {
        val MAX_GAP: Duration = Duration.ofMinutes(30)
    }
}
