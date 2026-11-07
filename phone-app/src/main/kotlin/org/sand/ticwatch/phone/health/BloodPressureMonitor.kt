package org.sand.ticwatch.phone.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BloodPressureRecord
import org.sand.ticwatch.phone.history.SampleEraser
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Client record id a saved reading is written to Health Connect under, so a deletion can find it again. */
object BloodPressureRecordIds {
    private const val PREFIX = "blood_pressure_"

    fun of(measuredAt: Instant): String = "$PREFIX${measuredAt.toEpochMilli()}"
}

/** Plausible cuff values the calibration form accepts (mmHg). */
object BloodPressureLimits {
    val SYSTOLIC = 70.0..250.0
    val DIASTOLIC = 40.0..150.0

    fun isPlausible(systolic: Double, diastolic: Double): Boolean =
        systolic in SYSTOLIC && diastolic in DIASTOLIC && diastolic < systolic
}

/** Reads the latest wrist signals and the recent step rate from the history (watch pushes arrive in batches). */
class WristSignalsReader(private val samples: SampleWindowReader) {

    /**
     * Heart rate as [live] when it is within [LIVE_HEART_RATE_MAX_AGE] of [now] (the watch's on-screen reading sent
     * with a measurement request, fresher than its batched pushes), else the median of the last [HEART_RATE_SPAN] of
     * watch readings within [HEART_RATE_LOOKBACK], else null (a cuff's pulse is stored as heart rate too, but isn't a
     * wrist signal).
     */
    suspend fun current(now: Instant, live: HeartRateReading? = null): WristSignals? {
        val heartRate = live?.takeIf { it.bpm in VALID_HEART_RATE && Duration.between(it.at, now).abs() <= LIVE_HEART_RATE_MAX_AGE }
            ?: storedHeartRate(now)
            ?: return null
        return WristSignals(
            heartRate = heartRate.bpm,
            heartRateAt = heartRate.at,
            rmssdMs = latestValue(SensorType.HRV_RMSSD, now),
            skinTemperatureCelsius = latestValue(SensorType.SKIN_TEMPERATURE, now),
        )
    }

    private suspend fun storedHeartRate(now: Instant): HeartRateReading? {
        val heartRates = heartRates(now.minus(HEART_RATE_LOOKBACK), now)
        val latest = heartRates.lastOrNull() ?: return null
        val recent = heartRates.filter { !it.at.isBefore(latest.at.minus(HEART_RATE_SPAN)) }.map { it.bpm }
        return HeartRateReading(recent.sorted()[recent.size / 2], latest.at)
    }

    /** Watch heart rates recorded in [from, to), ascending. */
    suspend fun heartRates(from: Instant, to: Instant): List<HeartRateReading> =
        samples.samples(setOf(SensorType.HEART_RATE), from, to, emptyList())
            .filter { it.value in VALID_HEART_RATE && it.source == SampleSource.WATCH }
            .map { HeartRateReading(it.value, it.recordedAt) }

    /** The day's resting heart rate, the calibration's fallback when no live heart rate is stored. */
    suspend fun restingHeartRate(now: Instant): Double? = latestValue(SensorType.RESTING_HEART_RATE, now, RESTING_LOOKBACK)

    /** Steps per minute over the last [REST_WINDOW]: the higher of stored cadence and steps spread over the window. */
    suspend fun recentStepRate(now: Instant): Double {
        val windowStart = now.minus(REST_WINDOW)
        val cadence = samples.samples(setOf(SensorType.STEPS_CADENCE), windowStart, now, emptyList()).maxOfOrNull { it.value } ?: 0.0
        // Step intervals that began before the window still count for their overlapping part.
        val steps = samples.samples(setOf(SensorType.STEPS), windowStart.minus(MAX_STEP_INTERVAL), now, emptyList())
            .sumOf { sample -> sample.value * sample.overlapFraction(windowStart, now) }
        return maxOf(cadence, steps / REST_WINDOW.toMinutes())
    }

    private suspend fun latestValue(type: SensorType, now: Instant, lookback: Duration = SIGNAL_LOOKBACK): Double? =
        samples.samples(setOf(type), now.minus(lookback), now, emptyList()).lastOrNull()?.value

    companion object {
        val REST_WINDOW: Duration = Duration.ofMinutes(5)
        val HEART_RATE_LOOKBACK: Duration = Duration.ofMinutes(30)
        private val HEART_RATE_SPAN = Duration.ofMinutes(5)
        private val LIVE_HEART_RATE_MAX_AGE = Duration.ofMinutes(2)
        private val SIGNAL_LOOKBACK = Duration.ofHours(3)
        private val RESTING_LOOKBACK = Duration.ofDays(2)
        private val MAX_STEP_INTERVAL = Duration.ofMinutes(15)
        private val VALID_HEART_RATE = 30.0..220.0
    }
}

/** A heart rate in bpm measured at [at]. */
data class HeartRateReading(val bpm: Double, val at: Instant)

/** Told after a reading was saved or deleted, e.g. to refresh the watch's copy of the recent readings. */
fun interface BloodPressureLogListener {
    fun onLogChanged()
}

/** Removes a saved reading's Health Connect record. */
fun interface BloodPressureRecordEraser {
    suspend fun erase(measuredAt: Instant)
}

class HealthConnectBloodPressureEraser(private val client: HealthConnectClient) : BloodPressureRecordEraser {
    override suspend fun erase(measuredAt: Instant) {
        client.deleteRecords(BloodPressureRecord::class, emptyList(), listOf(BloodPressureRecordIds.of(measuredAt)))
    }
}

/** Saved readings: written through the acknowledging writer (history + Health Connect), read back from the history. */
class BloodPressureLog(
    private val writer: HealthRecordWriter,
    private val samples: SampleWindowReader,
    private val historyEraser: SampleEraser,
    private val recordEraser: BloodPressureRecordEraser,
    private val listener: BloodPressureLogListener,
) {
    suspend fun save(reading: BloodPressureReading): Result<Unit> = writer.write(
        // Diastolic first: the systolic mapper looks its partner up in the history when building the record.
        listOfNotNull(
            SensorSample(SensorType.BLOOD_PRESSURE_DIASTOLIC, reading.diastolic, reading.measuredAt, source = reading.source),
            SensorSample(SensorType.BLOOD_PRESSURE_SYSTOLIC, reading.systolic, reading.measuredAt, source = reading.source),
            reading.pulse?.let { SensorSample(SensorType.HEART_RATE, it, reading.measuredAt, source = reading.source) },
        ),
    ).also { listener.onLogChanged() }

    /** Paired readings recorded in [from, to), ascending; a half without its partner is skipped. */
    suspend fun readings(from: Instant, to: Instant): List<BloodPressureReading> =
        samples.samples(TYPES, from, to, emptyList()).groupBy { it.recordedAt }.mapNotNull { (at, pair) ->
            val systolic = pair.firstOrNull { it.type == SensorType.BLOOD_PRESSURE_SYSTOLIC } ?: return@mapNotNull null
            val diastolic = pair.firstOrNull { it.type == SensorType.BLOOD_PRESSURE_DIASTOLIC } ?: return@mapNotNull null
            BloodPressureReading(systolic.value, diastolic.value, at, systolic.source, pulseAt(at, systolic.source))
        }

    /** The pulse saved with a cuff reading: a heart-rate row of the same source at the same instant. */
    private suspend fun pulseAt(at: Instant, source: SampleSource): Double? =
        if (source == SampleSource.WATCH) {
            null
        } else {
            samples.samples(setOf(SensorType.HEART_RATE), at, at.plusMillis(1), emptyList())
                .firstOrNull { it.source == source }?.value
        }

    suspend fun delete(reading: BloodPressureReading) {
        historyEraser.eraseSamples(if (reading.pulse == null) TYPES else TYPES + SensorType.HEART_RATE, reading.measuredAt)
        recordEraser.erase(reading.measuredAt)
        listener.onLogChanged()
    }

    private companion object {
        val TYPES = setOf(SensorType.BLOOD_PRESSURE_SYSTOLIC, SensorType.BLOOD_PRESSURE_DIASTOLIC)
    }
}

/** Outcome of [BloodPressureMonitor.measure]. */
sealed interface BloodPressureMeasurement {
    data object NotCalibrated : BloodPressureMeasurement
    data object NoHeartRate : BloodPressureMeasurement
    data class NotAtRest(val stepsPerMinute: Double) : BloodPressureMeasurement
    data class Estimated(val reading: BloodPressureReading, val signals: WristSignals) : BloodPressureMeasurement
}

/** Outcome of [BloodPressureMonitor.measureAndSave]; [saved] is null when nothing was estimated. */
data class SavedMeasurement(val measurement: BloodPressureMeasurement, val saved: Result<Unit>?)

/** Outcome of [BloodPressureMonitor.calibrate]. */
sealed interface CalibrationResult {
    data class Saved(val calibration: BloodPressureCalibration) : CalibrationResult
    data object Implausible : CalibrationResult
    data object NoHeartRate : CalibrationResult

    /** The reading is older than the calibration already in use. */
    data object Outdated : CalibrationResult

    /** The wrist signals at the cuff reading don't describe rest (stress, just after walking). */
    data class NotAtRest(val violation: CalibrationRestViolation) : CalibrationResult

    /** A hypertensive cuff reading was logged but didn't replace the normotensive calibration in use. */
    data object BaselineKept : CalibrationResult
}

/** Saved readings read back from the history. */
interface RecentBloodPressureReadings {
    /** The [limit] most recent readings, newest first. */
    suspend fun recent(limit: Int): List<BloodPressureReading>

    /** Readings from [from] until now, oldest first. */
    suspend fun since(from: Instant): List<BloodPressureReading>
}

/** The systolic correction in mmHg to add to an estimate made at a given moment. */
fun interface SystolicOffsetSource {
    suspend fun offsetAt(now: Instant): Double
}

/** Estimates now and saves the estimate, for measurements started remotely. */
fun interface BloodPressureMeasurer {
    suspend fun measureAndSave(liveHeartRate: HeartRateReading?): SavedMeasurement
}

/** Blood pressure use cases for the dashboard tab, the watch and the MCP tool: calibrate, estimate at rest, keep a log. */
class BloodPressureMonitor(
    private val calibrations: BloodPressureCalibrationStore,
    private val signals: WristSignalsReader,
    private val estimator: BloodPressureEstimator,
    private val systolicOffset: SystolicOffsetSource,
    private val restCheck: CalibrationRestCheck,
    private val restingFloor: RestingHeartRateFloor,
    private val pulseWave: RecentPulseWaveBloodPressure,
    private val blender: PulseWaveBlender,
    val log: BloodPressureLog,
    private val clock: Clock,
) : RecentBloodPressureReadings, BloodPressureMeasurer {
    suspend fun calibration(): BloodPressureCalibration? = calibrations.bloodPressureCalibration()

    fun isStale(calibration: BloodPressureCalibration): Boolean =
        Duration.between(calibration.calibratedAt, clock.instant()) > CALIBRATION_MAX_AGE

    /** Stores the cuff reading with the current wrist signals (resting heart rate if no live one is stored). */
    suspend fun calibrate(systolic: Double, diastolic: Double): CalibrationResult {
        if (!BloodPressureLimits.isPlausible(systolic, diastolic)) return CalibrationResult.Implausible
        val now = clock.instant()
        val current = signals.current(now)
        val heartRate = current?.heartRate ?: signals.restingHeartRate(now) ?: return CalibrationResult.NoHeartRate
        return store(systolic, diastolic, heartRate, current, now)
    }

    /**
     * Calibrates from a cuff reading taken at [measuredAt], but only if the watch has a heart rate within
     * [CUFF_SIGNAL_MAX_GAP] of it (no resting-HR fallback: the pair must describe the same moment) and the reading is
     * newer than the current calibration. Watch data arrives in batches, so NoHeartRate may just mean "not yet".
     * [liveHeartRate] (the watch's on-screen reading) is preferred when it is fresh, see [WristSignalsReader.current].
     * A hypertensive reading doesn't replace a fresh normotensive calibration ([CalibrationResult.BaselineKept]): one
     * spike would otherwise shift every later estimate. It stays in the log and still pairs with a pulse strip.
     */
    suspend fun calibrateFromCuff(
        systolic: Double,
        diastolic: Double,
        measuredAt: Instant,
        liveHeartRate: HeartRateReading? = null,
    ): CalibrationResult {
        if (!BloodPressureLimits.isPlausible(systolic, diastolic)) return CalibrationResult.Implausible
        val existing = calibration()
        if (existing?.calibratedAt?.isBefore(measuredAt) == false) return CalibrationResult.Outdated
        if (existing != null && keepsBaseline(existing, systolic, diastolic)) return CalibrationResult.BaselineKept
        val signalsAt = minOf(clock.instant(), measuredAt.plus(CUFF_SIGNAL_MAX_GAP))
        val current = signals.current(signalsAt, liveHeartRate)
            ?.takeIf { Duration.between(measuredAt, it.heartRateAt).abs() <= CUFF_SIGNAL_MAX_GAP }
            ?: return CalibrationResult.NoHeartRate
        restCheck.violation(current.heartRate, current.heartRateAt)?.let { return CalibrationResult.NotAtRest(it) }
        return store(systolic, diastolic, current.heartRate, current, measuredAt)
    }

    private fun keepsBaseline(existing: BloodPressureCalibration, systolic: Double, diastolic: Double): Boolean =
        isHypertensive(systolic, diastolic) && !isHypertensive(existing.systolic, existing.diastolic) && !isStale(existing)

    private fun isHypertensive(systolic: Double, diastolic: Double): Boolean =
        BloodPressureCategory.of(systolic, diastolic) >= HYPERTENSIVE_FROM

    private suspend fun store(
        systolic: Double,
        diastolic: Double,
        heartRate: Double,
        current: WristSignals?,
        at: Instant,
    ): CalibrationResult {
        val calibration = BloodPressureCalibration(
            systolic = systolic,
            diastolic = diastolic,
            heartRate = heartRate,
            rmssdMs = current?.rmssdMs,
            skinTemperatureCelsius = current?.skinTemperatureCelsius,
            calibratedAt = at,
        )
        calibrations.setBloodPressureCalibration(calibration)
        return CalibrationResult.Saved(calibration)
    }

    /**
     * Estimates from the stored wrist signals, preferring [liveHeartRate] (see [WristSignalsReader.current]), blended
     * with the pulse-wave estimate of a strip recorded shortly before when the personal model has one.
     */
    suspend fun measure(liveHeartRate: HeartRateReading? = null): BloodPressureMeasurement {
        val calibration = calibration() ?: return BloodPressureMeasurement.NotCalibrated
        val now = clock.instant()
        val stepRate = signals.recentStepRate(now)
        if (stepRate > REST_MAX_STEPS_PER_MINUTE) return BloodPressureMeasurement.NotAtRest(stepRate)
        val current = signals.current(now, liveHeartRate) ?: return BloodPressureMeasurement.NoHeartRate
        val wrist = estimator.estimate(calibration, current, now, systolicOffset.offsetAt(now), restingFloor.lowBpm(now))
        val reading = pulseWave.near(now)?.let { blender.blend(wrist, it) } ?: wrist
        return BloodPressureMeasurement.Estimated(reading, current)
    }

    /**
     * [measure], then saves an estimate straight to the log (a measurement started on the watch has no Save step).
     * A cuff reading of the last [CUFF_SIGNAL_MAX_GAP] that the calibration doesn't include yet (its watch heart rate
     * was still in the watch's buffer) is applied first, paired with [liveHeartRate].
     */
    override suspend fun measureAndSave(liveHeartRate: HeartRateReading?): SavedMeasurement {
        calibrateFromRecentCuff(liveHeartRate)
        val measurement = measure(liveHeartRate)
        return SavedMeasurement(measurement, (measurement as? BloodPressureMeasurement.Estimated)?.let { log.save(it.reading) })
    }

    private suspend fun calibrateFromRecentCuff(liveHeartRate: HeartRateReading?) {
        val now = clock.instant()
        val cuff = log.readings(now.minus(CUFF_SIGNAL_MAX_GAP), now.plusMillis(1))
            .lastOrNull { it.source == SampleSource.BLOOD_PRESSURE_CUFF }
            ?: return
        calibrateFromCuff(cuff.systolic, cuff.diastolic, cuff.measuredAt, liveHeartRate)
    }

    override suspend fun recent(limit: Int): List<BloodPressureReading> {
        val now = clock.instant()
        return log.readings(now.minus(RECENT_LOOKBACK), now.plusMillis(1)).takeLast(limit).asReversed()
    }

    override suspend fun since(from: Instant): List<BloodPressureReading> = log.readings(from, clock.instant().plusMillis(1))

    companion object {
        val CALIBRATION_MAX_AGE: Duration = Duration.ofDays(30)
        val CUFF_SIGNAL_MAX_GAP: Duration = Duration.ofMinutes(5)
        const val REST_MAX_STEPS_PER_MINUTE = 40.0
        private val HYPERTENSIVE_FROM = BloodPressureCategory.HYPERTENSION_GRADE_1
        private val RECENT_LOOKBACK = Duration.ofDays(400)
    }
}
