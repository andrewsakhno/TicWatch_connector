package org.sand.ticwatch.phone.health

import androidx.annotation.StringRes
import java.time.Instant
import org.sand.ticwatch.phone.R

/** Wrist signals at the moment of a calibration or estimate; RMSSD/skin temperature are null without recent data. */
data class WristSignals(
    val heartRate: Double,
    val heartRateAt: Instant,
    val rmssdMs: Double?,
    val skinTemperatureCelsius: Double?,
)

/** One systolic/diastolic pair in mmHg, as estimated or as stored in the history. */
/** [pulse] is only known for cuff readings; wrist estimates come from [SampleSource.WATCH]. */
data class BloodPressureReading(
    val systolic: Double,
    val diastolic: Double,
    val measuredAt: Instant,
    val source: SampleSource = SampleSource.WATCH,
    val pulse: Double? = null,
) {
    val category: BloodPressureCategory get() = BloodPressureCategory.of(systolic, diastolic)
}

/**
 * ESC/ESH office blood pressure categories; a reading falls in the highest category either value reaches.
 * [systolicFrom]/[diastolicFrom] are each category's lower bounds in mmHg.
 */
enum class BloodPressureCategory(@StringRes val label: Int, val systolicFrom: Double, val diastolicFrom: Double) {
    OPTIMAL(R.string.bp_category_optimal, 0.0, 0.0),
    NORMAL(R.string.bp_category_normal, 120.0, 80.0),
    HIGH_NORMAL(R.string.bp_category_high_normal, 130.0, 85.0),
    HYPERTENSION_GRADE_1(R.string.bp_category_hypertension_grade_1, 140.0, 90.0),
    HYPERTENSION_GRADE_2(R.string.bp_category_hypertension_grade_2, 160.0, 100.0),
    HYPERTENSION_GRADE_3(R.string.bp_category_hypertension_grade_3, 180.0, 110.0);

    companion object {
        fun of(systolic: Double, diastolic: Double): BloodPressureCategory =
            entries.last { systolic >= it.systolicFrom || diastolic >= it.diastolicFrom }
    }
}

/**
 * Tuning of [BloodPressureEstimator]: [systolicPerBpm]/[diastolicPerBpm] in mmHg per bpm of heart-rate change,
 * [systolicPerAutonomicShift] in mmHg per unit of `1 - RMSSD/RMSSD_base`, [systolicPerSkinDegree]/[diastolicPerSkinDegree]
 * in mmHg per °C of skin warming (peripheral vasodilation lowers both, vasoconstriction raises them). Not fitted to this user.
 */
data class BloodPressureCoefficients(
    val systolicPerBpm: Double = 0.85,
    val systolicPerAutonomicShift: Double = 14.0,
    val systolicPerSkinDegree: Double = 1.2,
    val diastolicPerBpm: Double = 0.3,
    val diastolicPerSkinDegree: Double = 1.5,
    val maxSystolicShift: Double = 35.0,
    val maxDiastolicShift: Double = 15.0,
    val minPulsePressure: Double = 20.0,
    /** Deepest systolic fall below the calibration while the pulse is still inside the personal resting corridor. */
    val maxRestingSystolicDrop: Double = 15.0,
)

/**
 * Calibrated cuffless estimate: shifts the calibration cuff reading by how the wrist signals moved since then.
 *
 * `SYS = SYS_base + k1 * dHR + k2 * (1 - RMSSD / RMSSD_base) - k5 * dT_skin + offset`, `DIA = DIA_base + k3 * dHR - k4 * dT_skin`,
 * each shift clamped to a physiological band around the baseline. A missing RMSSD or skin temperature (now or
 * at calibration) drops its term. [systolicOffset] comes from [SystolicOffsetSource] and is outside the clamp.
 * An approximation for trends only, not a substitute for a cuff.
 */
class BloodPressureEstimator(private val coefficients: BloodPressureCoefficients = BloodPressureCoefficients()) {

    fun estimate(
        calibration: BloodPressureCalibration,
        current: WristSignals,
        at: Instant,
        systolicOffset: Double = 0.0,
        restingHeartRateLow: Double? = null,
    ): BloodPressureReading {
        val heartRateShift = current.heartRate - calibration.heartRate
        val skinShift = skinShift(calibration.skinTemperatureCelsius, current.skinTemperatureCelsius)
        val systolicShift = (
            coefficients.systolicPerBpm * heartRateShift +
                coefficients.systolicPerAutonomicShift * autonomicShift(calibration.rmssdMs, current.rmssdMs) -
                coefficients.systolicPerSkinDegree * skinShift
            ).coerceIn(minSystolicShift(current.heartRate, restingHeartRateLow), coefficients.maxSystolicShift)
        val diastolicShift = (
            coefficients.diastolicPerBpm * heartRateShift -
                coefficients.diastolicPerSkinDegree * skinShift
            ).coerceIn(-coefficients.maxDiastolicShift, coefficients.maxDiastolicShift)
        val systolic = calibration.systolic + systolicShift + systolicOffset
        val diastolic = (calibration.diastolic + diastolicShift).coerceAtMost(systolic - coefficients.minPulsePressure)
        return BloodPressureReading(systolic, diastolic, at)
    }

    /**
     * A pulse still inside the resting corridor (at or above its [restingHeartRateLow]) is ordinary rest, which can't
     * justify a systolic collapse: the fall is capped at [BloodPressureCoefficients.maxRestingSystolicDrop]. Only below
     * the corridor (deep sleep, bradycardia) does the full band apply.
     */
    private fun minSystolicShift(heartRate: Double, restingHeartRateLow: Double?): Double =
        if (restingHeartRateLow != null && heartRate >= restingHeartRateLow) -coefficients.maxRestingSystolicDrop
        else -coefficients.maxSystolicShift

    /** `1 - current / baseline`, bounded so one noisy RMSSD can't dominate; 0 without both values. */
    private fun autonomicShift(baseline: Double?, current: Double?): Double =
        if (baseline == null || current == null || baseline <= 0.0) 0.0
        else (1.0 - current / baseline).coerceIn(-MAX_AUTONOMIC_SHIFT, MAX_AUTONOMIC_SHIFT)

    private fun skinShift(baseline: Double?, current: Double?): Double =
        if (baseline == null || current == null) 0.0 else current - baseline

    private companion object {
        const val MAX_AUTONOMIC_SHIFT = 1.0
    }
}
