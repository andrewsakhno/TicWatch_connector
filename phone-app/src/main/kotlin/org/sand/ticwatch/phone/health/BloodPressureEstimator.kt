package org.sand.ticwatch.phone.health

import java.time.Instant

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
enum class BloodPressureCategory(val label: String, val systolicFrom: Double, val diastolicFrom: Double) {
    OPTIMAL("Optimal", 0.0, 0.0),
    NORMAL("Normal", 120.0, 80.0),
    HIGH_NORMAL("High normal", 130.0, 85.0),
    HYPERTENSION_GRADE_1("Hypertension grade 1", 140.0, 90.0),
    HYPERTENSION_GRADE_2("Hypertension grade 2", 160.0, 100.0),
    HYPERTENSION_GRADE_3("Hypertension grade 3", 180.0, 110.0);

    companion object {
        fun of(systolic: Double, diastolic: Double): BloodPressureCategory =
            entries.last { systolic >= it.systolicFrom || diastolic >= it.diastolicFrom }
    }
}

/**
 * Tuning of [BloodPressureEstimator]: [systolicPerBpm]/[diastolicPerBpm] in mmHg per bpm of heart-rate change,
 * [systolicPerAutonomicShift] in mmHg per unit of `1 - RMSSD/RMSSD_base`, [diastolicPerSkinDegree] in mmHg per °C
 * of skin warming (peripheral vasodilation lowers diastolic). Literature-order values, not fitted to this user.
 */
data class BloodPressureCoefficients(
    val systolicPerBpm: Double = 0.5,
    val systolicPerAutonomicShift: Double = 8.0,
    val diastolicPerBpm: Double = 0.3,
    val diastolicPerSkinDegree: Double = 1.5,
    val maxSystolicShift: Double = 25.0,
    val maxDiastolicShift: Double = 15.0,
    val minPulsePressure: Double = 20.0,
)

/**
 * Calibrated cuffless estimate: shifts the calibration cuff reading by how the wrist signals moved since then.
 *
 * `SYS = SYS_base + k1 * dHR + k2 * (1 - RMSSD / RMSSD_base)`, `DIA = DIA_base + k3 * dHR - k4 * dT_skin`,
 * each shift clamped to a physiological band around the baseline. A missing RMSSD or skin temperature (now or
 * at calibration) drops its term. An approximation for trends only, not a substitute for a cuff.
 */
class BloodPressureEstimator(private val coefficients: BloodPressureCoefficients = BloodPressureCoefficients()) {

    fun estimate(calibration: BloodPressureCalibration, current: WristSignals, at: Instant): BloodPressureReading {
        val heartRateShift = current.heartRate - calibration.heartRate
        val systolicShift = (
            coefficients.systolicPerBpm * heartRateShift +
                coefficients.systolicPerAutonomicShift * autonomicShift(calibration.rmssdMs, current.rmssdMs)
            ).coerceIn(-coefficients.maxSystolicShift, coefficients.maxSystolicShift)
        val diastolicShift = (
            coefficients.diastolicPerBpm * heartRateShift -
                coefficients.diastolicPerSkinDegree * skinShift(calibration.skinTemperatureCelsius, current.skinTemperatureCelsius)
            ).coerceIn(-coefficients.maxDiastolicShift, coefficients.maxDiastolicShift)
        val systolic = calibration.systolic + systolicShift
        val diastolic = (calibration.diastolic + diastolicShift).coerceAtMost(systolic - coefficients.minPulsePressure)
        return BloodPressureReading(systolic, diastolic, at)
    }

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
