package org.sand.ticwatch.phone.report

import org.sand.ticwatch.phone.health.BiologicalSex
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.medication.Medication
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Profile data printed in the report header; null where the profile is empty. */
data class PatientInfo(val name: String?, val ageYears: Int?, val sex: BiologicalSex?, val heightCm: Double?, val weightKg: Double?)

/** Mean systolic/diastolic of [count] readings, mmHg. */
data class PressureMean(val systolic: Double, val diastolic: Double, val count: Int) {
    val pulsePressure: Double get() = systolic - diastolic
}

/** Nocturnal systolic dip as a share of the daytime mean; [minPercent] is each pattern's lower bound. */
enum class DippingPattern(val label: String, val minPercent: Double) {
    REVERSE_DIPPER("Reverse dipper (riser)", Double.NEGATIVE_INFINITY),
    NON_DIPPER("Non-dipper", 0.0),
    DIPPER("Dipper", 10.0),
    EXTREME_DIPPER("Extreme dipper", 20.0);

    companion object {
        fun of(percent: Double): DippingPattern = entries.last { percent >= it.minPercent }
    }
}

/** Ambulatory blood pressure summary; a mean is null without readings in its period. */
data class AmbulatoryPressureSummary(
    val overall: PressureMean?,
    val daytime: PressureMean?,
    val nighttime: PressureMean?,
    val cuffReadings: Int,
    val wristReadings: Int,
) {
    /** `(day SYS - night SYS) / day SYS`, in percent. */
    val dippingPercent: Double?
        get() = if (daytime != null && nighttime != null) (daytime.systolic - nighttime.systolic) / daytime.systolic * PERCENT else null

    val dippingPattern: DippingPattern? get() = dippingPercent?.let(DippingPattern::of)

    private companion object {
        const val PERCENT = 100.0
    }
}

/** Heart rate summary: night baseline, morning surge, daytime tachycardia and the medication's onset. */
data class HeartRateSummary(
    val nightRestingHeartRate: Double?,
    val nights: Int,
    val morningSurge: Double?,
    val tachycardiaEpisodes: Int,
    val tachycardiaDuration: Duration,
    val medicationOnset: Duration?,
    val intakesWithOnset: Int,
    val intakes: Int,
)

/** A cuff reading next to the nearest wrist estimate; deltas are wrist minus cuff. */
data class CuffComparison(val cuff: BloodPressureReading, val wrist: BloodPressureReading) {
    val systolicDelta: Double get() = wrist.systolic - cuff.systolic
    val diastolicDelta: Double get() = wrist.diastolic - cuff.diastolic
}

/** The cuff reference readings of the period; [comparisons] are those with a wrist estimate close enough. */
data class CalibrationAccuracy(val cuffReadings: List<BloodPressureReading>, val comparisons: List<CuffComparison>) {
    val meanAbsoluteSystolicDelta: Double? get() = comparisons.map { kotlin.math.abs(it.systolicDelta) }.average().takeUnless { it.isNaN() }
    val meanAbsoluteDiastolicDelta: Double? get() = comparisons.map { kotlin.math.abs(it.diastolicDelta) }.average().takeUnless { it.isNaN() }
}

/** Everything the doctor's report shows for [from, to). */
data class CardioReport(
    val generatedAt: Instant,
    val from: Instant,
    val to: Instant,
    val zone: ZoneId,
    val patient: PatientInfo,
    val therapy: List<Medication>,
    val pressure: AmbulatoryPressureSummary,
    val heartRate: HeartRateSummary,
    val accuracy: CalibrationAccuracy,
)
