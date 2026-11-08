package org.sand.ticwatch.phone.health

import androidx.annotation.StringRes
import java.time.Instant
import kotlin.math.tanh
import org.sand.ticwatch.phone.R

/**
 * Wrist signals at the moment of a calibration or estimate; RMSSD/skin/ambient temperature are null without recent data.
 * [ambientTemperatureCelsius] is the watch case sensor, only used to tell environmental cooling from vasoconstriction.
 */
data class WristSignals(
    val heartRate: Double,
    val heartRateAt: Instant,
    val rmssdMs: Double?,
    val skinTemperatureCelsius: Double?,
    val ambientTemperatureCelsius: Double? = null,
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
 * [systolicPerAutonomicShift]/[diastolicPerAutonomicShift] in mmHg per unit of `1 - RMSSD/RMSSD_base`, [systolicPerSkinDegree]/[diastolicPerSkinDegree]
 * in mmHg per °C of skin warming (peripheral vasodilation lowers both, vasoconstriction raises them). Not fitted to this user.
 */
data class BloodPressureCoefficients(
    val systolicPerBpm: Double = 0.85,
    val systolicPerAutonomicShift: Double = 14.0,
    val systolicPerSkinDegree: Double = 1.2,
    val diastolicPerBpm: Double = 0.3,
    val diastolicPerAutonomicShift: Double = 5.0,
    val diastolicPerSkinDegree: Double = 1.5,
    val maxSystolicShift: Double = 35.0,
    val maxDiastolicShift: Double = 15.0,
    val minPulsePressure: Double = 20.0,
    /** Deepest systolic fall below the calibration while the pulse is still inside the personal resting corridor. */
    val maxRestingSystolicDrop: Double = 15.0,
    /**
     * The resting floor is measured from at most this systolic: a calibration taken during a hypertensive spike
     * mustn't hold every later resting estimate at `calibration - maxRestingSystolicDrop`.
     */
    val normotensiveSystolicReference: Double = 135.0,
    /** Skin temperature change (°C) the shift saturates towards: `max * tanh(dT / max)`. */
    val maxEffectiveSkinDelta: Double = 2.5,
    /** Below this skin temperature (°C) a cooling wrist is losing heat to the environment, not vasoconstricting centrally. */
    val coldSkinThresholdCelsius: Double = 29.5,
    /** Below this watch case temperature (°C) the wrist is exposed to cold air. */
    val coldAmbientThresholdCelsius: Double = 28.5,
    /** Skin temperature (°C) at which cold-exposure attenuation removes the cooling shift entirely. */
    val coldSkinFloorCelsius: Double = 25.0,
)

/**
 * Calibrated cuffless estimate: shifts the calibration cuff reading by how the wrist signals moved since then.
 *
 * `SYS = SYS_base + k1 * dHR + k2 * A - k5 * dT_skin + offset`, `DIA = DIA_base + k3 * dHR + k6 * A - k4 * dT_skin`
 * with `A = 1 - RMSSD / RMSSD_base`,
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
        val skinShift = skinShift(calibration.skinTemperatureCelsius, current.skinTemperatureCelsius, current.ambientTemperatureCelsius)
        val autonomicShift = autonomicShift(calibration.rmssdMs, current.rmssdMs)
        val systolicShift = (
            coefficients.systolicPerBpm * heartRateShift +
                coefficients.systolicPerAutonomicShift * autonomicShift -
                coefficients.systolicPerSkinDegree * skinShift
            ).coerceIn(minSystolicShift(calibration.systolic, current.heartRate, restingHeartRateLow), coefficients.maxSystolicShift)
        val diastolicShift = (
            coefficients.diastolicPerBpm * heartRateShift +
                coefficients.diastolicPerAutonomicShift * autonomicShift -
                coefficients.diastolicPerSkinDegree * skinShift
            ).coerceIn(-coefficients.maxDiastolicShift, coefficients.maxDiastolicShift)
        val systolic = calibration.systolic + systolicShift + systolicOffset
        val diastolic = (calibration.diastolic + diastolicShift).coerceAtMost(systolic - coefficients.minPulsePressure)
        return BloodPressureReading(systolic, diastolic, at)
    }

    /**
     * A pulse still inside the resting corridor (at or above its [restingHeartRateLow]) is ordinary rest, which can't
     * justify a systolic collapse: the floor is [BloodPressureCoefficients.maxRestingSystolicDrop] below the calibration,
     * or below [BloodPressureCoefficients.normotensiveSystolicReference] for a hypertensive calibration. Only below the
     * corridor (deep sleep, bradycardia) does the full band apply; the floor never goes past it.
     */
    private fun minSystolicShift(calibrationSystolic: Double, heartRate: Double, restingHeartRateLow: Double?): Double {
        if (restingHeartRateLow == null || heartRate < restingHeartRateLow) return -coefficients.maxSystolicShift
        val excess = (calibrationSystolic - coefficients.normotensiveSystolicReference).coerceAtLeast(0.0)
        return -(coefficients.maxRestingSystolicDrop + excess).coerceAtMost(coefficients.maxSystolicShift)
    }

    /** `1 - current / baseline`, bounded so one noisy RMSSD can't dominate; 0 without both values. */
    private fun autonomicShift(baseline: Double?, current: Double?): Double =
        if (baseline == null || current == null || baseline <= 0.0) 0.0
        else (1.0 - current / baseline).coerceIn(-MAX_AUTONOMIC_SHIFT, MAX_AUTONOMIC_SHIFT)

    /**
     * Effective skin temperature change: soft-saturated at [BloodPressureCoefficients.maxEffectiveSkinDelta], and a
     * cooling shift is scaled down towards 0 as the skin nears [BloodPressureCoefficients.coldSkinFloorCelsius] while
     * the skin or the air is cold, since that cooling is environmental heat loss rather than sympathetic tone.
     */
    private fun skinShift(baseline: Double?, current: Double?, ambient: Double?): Double {
        if (baseline == null || current == null) return 0.0
        val rawDelta = current - baseline
        val saturated = coefficients.maxEffectiveSkinDelta * tanh(rawDelta / coefficients.maxEffectiveSkinDelta)
        val coldExposure = current < coefficients.coldSkinThresholdCelsius ||
            (ambient != null && ambient < coefficients.coldAmbientThresholdCelsius)
        if (rawDelta >= 0.0 || !coldExposure) return saturated
        val attenuation = ((current - coefficients.coldSkinFloorCelsius) /
            (coefficients.coldSkinThresholdCelsius - coefficients.coldSkinFloorCelsius)).coerceIn(0.0, 1.0)
        return saturated * attenuation
    }

    private companion object {
        const val MAX_AUTONOMIC_SHIFT = 1.0
    }
}
