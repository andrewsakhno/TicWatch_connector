package org.sand.ticwatch.watch.bloodpressure

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * The phone's active cuff calibration plus the systolic offset in force when it was sent, cached so the watch can
 * estimate while the phone is unreachable. Mirrors phone-app's `health/BloodPressureCalibration` and
 * `datalayer/WatchBloodPressureCalibrationPublisher.kt`.
 */
data class WatchBloodPressureCalibration(
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Double,
    val rmssdMs: Double?,
    val skinTemperatureCelsius: Double?,
    val calibratedAt: Instant,
    val systolicOffset: Double,
) {
    /** Same limit as the phone's `BloodPressureMonitor.CALIBRATION_MAX_AGE`. */
    fun isStale(now: Instant): Boolean = Duration.between(calibratedAt, now) > MAX_AGE

    private companion object {
        val MAX_AGE: Duration = Duration.ofDays(30)
    }
}

/** Decodes [org.sand.ticwatch.watch.datalayer.DataLayerPaths.MESSAGE_BLOOD_PRESSURE_CALIBRATION]. */
object BloodPressureCalibrationPayload {
    private const val SYSTOLIC = "systolic"
    private const val DIASTOLIC = "diastolic"
    private const val HEART_RATE = "heart_rate"
    private const val RMSSD = "rmssd"
    private const val SKIN_TEMPERATURE = "skin_temperature"
    private const val CALIBRATED_AT = "calibrated_at"
    private const val SYSTOLIC_OFFSET = "systolic_offset"

    fun decode(payload: String): WatchBloodPressureCalibration = JSONObject(payload).let {
        WatchBloodPressureCalibration(
            systolic = it.getDouble(SYSTOLIC),
            diastolic = it.getDouble(DIASTOLIC),
            heartRate = it.getDouble(HEART_RATE),
            rmssdMs = if (it.has(RMSSD)) it.getDouble(RMSSD) else null,
            skinTemperatureCelsius = if (it.has(SKIN_TEMPERATURE)) it.getDouble(SKIN_TEMPERATURE) else null,
            calibratedAt = Instant.ofEpochMilli(it.getLong(CALIBRATED_AT)),
            systolicOffset = it.optDouble(SYSTOLIC_OFFSET, 0.0),
        )
    }
}

/** Wrist signals the watch has at hand for a local estimate; RMSSD/skin temperature are null without a recent reading. */
data class LocalWristSignals(val heartRate: Double, val rmssdMs: Double?, val skinTemperatureCelsius: Double?)

/** A systolic/diastolic pair in mmHg. */
data class LocalBloodPressure(val systolic: Double, val diastolic: Double)

/**
 * Same model and coefficients as phone-app's `health/BloodPressureEstimator` (keep in sync): the calibration cuff
 * reading shifted by how heart rate, RMSSD and skin temperature moved since, each shift clamped, plus the offset. While
 * the pulse is at or above [restingHeartRateLow] (inside the personal resting corridor) the systolic fall is capped at
 * [MAX_RESTING_SYSTOLIC_DROP] below the calibration, or below [NORMOTENSIVE_SYSTOLIC_REFERENCE] for a hypertensive one.
 */
class LocalBloodPressureEstimator {

    fun estimate(calibration: WatchBloodPressureCalibration, current: LocalWristSignals, restingHeartRateLow: Double?): LocalBloodPressure {
        val heartRateShift = current.heartRate - calibration.heartRate
        val skinShift = shift(calibration.skinTemperatureCelsius, current.skinTemperatureCelsius)
        val autonomicShift = autonomicShift(calibration.rmssdMs, current.rmssdMs)
        val systolicShift = (
            SYSTOLIC_PER_BPM * heartRateShift +
                SYSTOLIC_PER_AUTONOMIC_SHIFT * autonomicShift -
                SYSTOLIC_PER_SKIN_DEGREE * skinShift
            ).coerceIn(minSystolicShift(calibration.systolic, current.heartRate, restingHeartRateLow), MAX_SYSTOLIC_SHIFT)
        val diastolicShift = (
            DIASTOLIC_PER_BPM * heartRateShift +
                DIASTOLIC_PER_AUTONOMIC_SHIFT * autonomicShift -
                DIASTOLIC_PER_SKIN_DEGREE * skinShift
            )
            .coerceIn(-MAX_DIASTOLIC_SHIFT, MAX_DIASTOLIC_SHIFT)
        val systolic = calibration.systolic + systolicShift + calibration.systolicOffset
        val diastolic = (calibration.diastolic + diastolicShift).coerceAtMost(systolic - MIN_PULSE_PRESSURE)
        return LocalBloodPressure(systolic, diastolic)
    }

    private fun minSystolicShift(calibrationSystolic: Double, heartRate: Double, restingHeartRateLow: Double?): Double {
        if (restingHeartRateLow == null || heartRate < restingHeartRateLow) return -MAX_SYSTOLIC_SHIFT
        val excess = (calibrationSystolic - NORMOTENSIVE_SYSTOLIC_REFERENCE).coerceAtLeast(0.0)
        return -(MAX_RESTING_SYSTOLIC_DROP + excess).coerceAtMost(MAX_SYSTOLIC_SHIFT)
    }

    private fun autonomicShift(baseline: Double?, current: Double?): Double =
        if (baseline == null || current == null || baseline <= 0.0) 0.0
        else (1.0 - current / baseline).coerceIn(-MAX_AUTONOMIC_SHIFT, MAX_AUTONOMIC_SHIFT)

    private fun shift(baseline: Double?, current: Double?): Double =
        if (baseline == null || current == null) 0.0 else current - baseline

    private companion object {
        const val SYSTOLIC_PER_BPM = 0.85
        const val SYSTOLIC_PER_AUTONOMIC_SHIFT = 14.0
        const val SYSTOLIC_PER_SKIN_DEGREE = 1.2
        const val DIASTOLIC_PER_BPM = 0.3
        const val DIASTOLIC_PER_AUTONOMIC_SHIFT = 5.0
        const val DIASTOLIC_PER_SKIN_DEGREE = 1.5
        const val MAX_SYSTOLIC_SHIFT = 35.0
        const val MAX_DIASTOLIC_SHIFT = 15.0
        const val MIN_PULSE_PRESSURE = 20.0
        const val MAX_RESTING_SYSTOLIC_DROP = 15.0
        const val NORMOTENSIVE_SYSTOLIC_REFERENCE = 135.0
        const val MAX_AUTONOMIC_SHIFT = 1.0
    }
}
