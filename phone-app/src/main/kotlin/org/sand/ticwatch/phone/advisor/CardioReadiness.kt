package org.sand.ticwatch.phone.advisor

import androidx.annotation.StringRes
import java.time.Instant
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.insights.FatigueIndex
import org.sand.ticwatch.phone.medication.MedicationProtection
import org.sand.ticwatch.phone.sleep.VagalRecovery
import org.sand.ticwatch.phone.text.UiText

/** Readiness bands, descending; [minScore] is where each starts. */
enum class ReadinessState(
    @StringRes val label: Int,
    val minScore: Int,
    val defaultAction: AdviceAction,
    @StringRes val guidance: Int,
) {
    OPTIMAL(R.string.readiness_optimal, 80, AdviceAction.ACTIVE, R.string.readiness_optimal_guidance),
    MODERATE(R.string.readiness_moderate, 55, AdviceAction.CALM_WALK, R.string.readiness_moderate_guidance),
    LIMITED(R.string.readiness_limited, 35, AdviceAction.ELEVATOR, R.string.readiness_limited_guidance),
    UNLOAD(R.string.readiness_unload, 0, AdviceAction.CUFF_CHECK, R.string.readiness_unload_guidance);

    companion object {
        fun of(score: Int): ReadinessState = entries.first { score >= it.minScore }
    }
}

/** The one concrete step shown on the advice card. */
enum class AdviceAction(val icon: String, @StringRes val title: Int) {
    ACTIVE("🏃", R.string.action_active),
    CALM_WALK("🚶", R.string.action_calm_walk),
    ELEVATOR("🛗", R.string.action_elevator),
    NO_STIMULANTS("☕", R.string.action_no_stimulants),
    CUFF_CHECK("🫀", R.string.action_cuff_check),
}

/** What a [ReadinessFactor] is about. */
enum class ReadinessFactorKind(@StringRes val label: Int) {
    DIASTOLIC(R.string.factor_diastolic),
    HEART_RATE(R.string.factor_pulse),
    HRV(R.string.factor_hrv),
    FATIGUE(R.string.factor_fatigue),
    FATIGUE_INDEX(R.string.factor_fatigue_index),
    GEOMAGNETIC(R.string.factor_geomagnetic),
    RECOVERY(R.string.factor_recovery),
    TRAINING_LOAD(R.string.factor_training_load),
    MEDICATION(R.string.factor_medication),
}

/** One input's effect on the score: [points] < 0 limits, > 0 helps, 0 is informational. */
data class ReadinessFactor(val kind: ReadinessFactorKind, val detail: UiText, val points: Int) {
    val isLimiting: Boolean get() = points < 0
}

/** The advice card: [action] and its explanation in plain language with the reasons behind it. */
data class Advice(val action: AdviceAction, val text: UiText, val rationale: UiText?)

/** Result of [CardioReadinessEngine.assess]. */
data class ReadinessAssessment(
    val score: Int,
    val state: ReadinessState,
    val advice: Advice,
    val factors: List<ReadinessFactor>,
    val at: Instant,
) {
    /** Limiting factors, the strongest first. */
    val limitingFactors: List<ReadinessFactor> get() = factors.filter { it.isLimiting }.sortedBy { it.points }
}

/** One forecast hour; [walkWindow] marks a daytime hour favourable for walking. */
data class ReadinessForecastHour(val start: Instant, val score: Int, val state: ReadinessState, val walkWindow: Boolean)

/** Latest vitals for the screen header and the widget, each null without recent data. */
data class Vitals(
    val pressure: BloodPressureReading?,
    val heartRateBpm: Double?,
    val bodyTemperatureCelsius: Double?,
    val spo2Percent: Double?,
)

/** Everything the Advisor tab, its widget and the MCP tool show; [recovery] is null without last night's RMSSD, [fatigue] with too few signals. */
data class CardioReadiness(
    val assessment: ReadinessAssessment,
    val forecast: List<ReadinessForecastHour>,
    val vitals: Vitals,
    val medication: MedicationProtection,
    val recovery: VagalRecovery?,
    val fatigue: FatigueIndex?,
) {
    val nextWalkWindow: ReadinessForecastHour? get() = forecast.firstOrNull { it.walkWindow }
}

fun interface CardioReadinessSource {
    suspend fun load(): CardioReadiness
}
