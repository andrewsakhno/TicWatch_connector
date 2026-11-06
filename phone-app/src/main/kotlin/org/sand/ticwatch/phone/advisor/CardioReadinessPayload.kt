package org.sand.ticwatch.phone.advisor

import kotlin.math.roundToInt
import org.json.JSONObject
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.insights.FatigueLevel
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.text.uiText

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

        fun of(fatigue: FatigueLevel): VitalLevel = when (fatigue) {
            FatigueLevel.FRESH -> GOOD
            FatigueLevel.MODERATE -> FAIR
            FatigueLevel.PRONOUNCED -> ELEVATED
            FatigueLevel.EXHAUSTED -> HIGH
        }

        fun of(tone: AutonomicTone): VitalLevel = when (tone) {
            AutonomicTone.CALM -> GOOD
            AutonomicTone.RECOVERING -> FAIR
            AutonomicTone.STRAINED, AutonomicTone.POST_STRESS -> ELEVATED
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
 * JSON of [CardioReadiness] for the watch's readiness tile, page and face: the score, the load readiness and the latest pressure and pulse, each with
 * its [VitalLevel] so the watch only maps levels to colours, plus the state and advice labels. The autonomic keys (balance, level,
 * tone label, post-stress flag) are optional like the load ones, so either side may be older. Mirrors watch-app's
 * `readiness/CardioReadinessPayload.kt` (no shared module, keep the keys in sync).
 */
object CardioReadinessPayload {
    const val SCORE = "score"
    const val SCORE_LEVEL = "score_level"
    const val SYSTOLIC = "sys"
    const val DIASTOLIC = "dia"
    const val PRESSURE_LEVEL = "bp_level"
    const val HEART_RATE = "hr"
    const val HEART_RATE_LEVEL = "hr_level"
    const val STATE_LABEL = "state_label"
    const val ADVICE = "advice"
    const val LOAD_READINESS = "load_readiness"
    const val LOAD_LEVEL = "load_level"
    const val FATIGUE_LABEL = "fatigue_label"
    const val AUTONOMIC_READINESS = "autonomic_readiness"
    const val AUTONOMIC_LEVEL = "autonomic_level"
    const val AUTONOMIC_LABEL = "autonomic_label"
    const val POST_STRESS = "post_stress"
    const val AT = "at"

    /** The content of a payload apart from when it was computed, which differs on every read. */
    fun withoutTimestamp(payload: String): String = JSONObject(payload).apply { remove(AT) }.toString()

    /** [text] resolves the state, advice and fatigue labels, which the watch displays as they are. */
    fun encode(readiness: CardioReadiness, text: TextResolver): String {
        val assessment = readiness.assessment
        val json = JSONObject()
            .put(SCORE, assessment.score)
            .put(SCORE_LEVEL, VitalLevel.of(assessment.state).code)
            .put(STATE_LABEL, text.resolve(uiText(assessment.state.label)))
            .put(ADVICE, text.resolve(uiText(assessment.advice.action.title)))
            .put(AT, assessment.at.toEpochMilli())
        readiness.vitals.pressure?.let {
            json.put(SYSTOLIC, it.systolic.roundToInt())
                .put(DIASTOLIC, it.diastolic.roundToInt())
                .put(PRESSURE_LEVEL, VitalLevel.of(it).code)
        }
        readiness.vitals.heartRateBpm?.let {
            json.put(HEART_RATE, it.roundToInt()).put(HEART_RATE_LEVEL, VitalLevel.ofHeartRate(it).code)
        }
        readiness.fatigue?.let {
            json.put(LOAD_READINESS, it.loadReadinessPercent)
                .put(LOAD_LEVEL, VitalLevel.of(it.level).code)
                .put(FATIGUE_LABEL, text.resolve(uiText(it.level.label)))
        }
        readiness.autonomic?.let {
            json.put(AUTONOMIC_READINESS, it.percent)
                .put(AUTONOMIC_LEVEL, VitalLevel.of(it.tone).code)
                .put(AUTONOMIC_LABEL, text.resolve(uiText(it.tone.label)))
                .put(POST_STRESS, it.postStressActive)
        }
        return json.toString()
    }
}
