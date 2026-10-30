package org.sand.ticwatch.phone.advisor

import kotlin.math.roundToInt
import org.json.JSONObject
import org.sand.ticwatch.phone.health.BloodPressureReading

/** How concerning a value on the watch's readiness tile is; [code] travels on the wire (never `ordinal`). */
enum class VitalLevel(val code: Int) {
    GOOD(0),
    FAIR(1),
    ELEVATED(2),
    HIGH(3);

    companion object {
        fun of(state: ReadinessState): VitalLevel = when (state) {
            ReadinessState.OPTIMAL -> GOOD
            ReadinessState.MODERATE -> FAIR
            ReadinessState.LIMITED -> ELEVATED
            ReadinessState.UNLOAD -> HIGH
        }

        /** ESC office bands: optimal/normal, high-normal, grade 1, grade 2+; the worse of both halves wins. */
        fun of(pressure: BloodPressureReading): VitalLevel = maxOf(
            band(pressure.systolic, SYSTOLIC_BANDS),
            band(pressure.diastolic, DIASTOLIC_BANDS),
        )

        /** Resting pulse: 50-90 normal, 90-100 borderline, 100-120 (or under 45) elevated, above 120 high. */
        fun ofHeartRate(bpm: Double): VitalLevel = when {
            bpm > HR_HIGH_ABOVE -> HIGH
            bpm > HR_ELEVATED_ABOVE || bpm < HR_ELEVATED_BELOW -> ELEVATED
            bpm > HR_FAIR_ABOVE || bpm < HR_FAIR_BELOW -> FAIR
            else -> GOOD
        }

        /** [upperBounds] are the exclusive upper limits of GOOD, FAIR and ELEVATED. */
        private fun band(value: Double, upperBounds: List<Double>): VitalLevel =
            entries[upperBounds.indexOfFirst { value < it }.takeIf { it >= 0 } ?: upperBounds.size]

        private val SYSTOLIC_BANDS = listOf(130.0, 140.0, 160.0)
        private val DIASTOLIC_BANDS = listOf(85.0, 90.0, 100.0)
        private const val HR_HIGH_ABOVE = 120.0
        private const val HR_ELEVATED_ABOVE = 100.0
        private const val HR_ELEVATED_BELOW = 45.0
        private const val HR_FAIR_ABOVE = 90.0
        private const val HR_FAIR_BELOW = 50.0
    }
}

/**
 * JSON of [CardioReadiness] for the watch's readiness tile: the score and the latest pressure and pulse, each with
 * its [VitalLevel] so the watch only maps levels to colours. Mirrors watch-app's `readiness/CardioReadinessPayload.kt`
 * (no shared module, keep the keys in sync).
 */
object CardioReadinessPayload {
    const val SCORE = "score"
    const val SCORE_LEVEL = "score_level"
    const val SYSTOLIC = "sys"
    const val DIASTOLIC = "dia"
    const val PRESSURE_LEVEL = "bp_level"
    const val HEART_RATE = "hr"
    const val HEART_RATE_LEVEL = "hr_level"
    const val AT = "at"

    fun encode(readiness: CardioReadiness): String {
        val assessment = readiness.assessment
        val json = JSONObject()
            .put(SCORE, assessment.score)
            .put(SCORE_LEVEL, VitalLevel.of(assessment.state).code)
            .put(AT, assessment.at.toEpochMilli())
        readiness.vitals.pressure?.let {
            json.put(SYSTOLIC, it.systolic.roundToInt())
                .put(DIASTOLIC, it.diastolic.roundToInt())
                .put(PRESSURE_LEVEL, VitalLevel.of(it).code)
        }
        readiness.vitals.heartRateBpm?.let {
            json.put(HEART_RATE, it.roundToInt()).put(HEART_RATE_LEVEL, VitalLevel.ofHeartRate(it).code)
        }
        return json.toString()
    }
}
