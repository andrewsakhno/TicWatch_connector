package org.sand.ticwatch.phone.advisor

import org.sand.ticwatch.phone.health.BloodPressureReading
import java.time.Instant

/** Readiness bands, descending; [minScore] is where each starts. */
enum class ReadinessState(val label: String, val minScore: Int, val defaultAction: AdviceAction, val guidance: String) {
    OPTIMAL(
        "Optimal", 80, AdviceAction.ACTIVE,
        "Your cardiovascular system is ready for activity: uphill walking, brisk walking and workouts are fine.",
    ),
    MODERATE(
        "Moderate load", 55, AdviceAction.CALM_WALK,
        "Keep a steady base pace. Avoid sudden bursts, stay hydrated, take stairs slowly while controlling your breathing.",
    ),
    LIMITED(
        "Limit cardio load", 35, AdviceAction.ELEVATOR,
        "Vascular tone or resting pulse is raised. Take the elevator instead of stairs, postpone strenuous tasks and " +
            "rest for 15-20 minutes.",
    ),
    UNLOAD(
        "Rest and monitor", 0, AdviceAction.CUFF_CHECK,
        "High load on the heart muscle. Avoid physical effort: rest half-reclined, drink a glass of water and take a " +
            "control measurement with the Bioland cuff.",
    );

    companion object {
        fun of(score: Int): ReadinessState = entries.first { score >= it.minScore }
    }
}

/** The one concrete step shown on the advice card. */
enum class AdviceAction(val icon: String, val title: String) {
    ACTIVE("🏃", "Activity is fine"),
    CALM_WALK("🚶", "Calm walking"),
    ELEVATOR("🛗", "Elevator instead of stairs"),
    NO_STIMULANTS("☕", "Skip coffee and stimulants"),
    CUFF_CHECK("🫀", "Check with the cuff"),
}

/** What a [ReadinessFactor] is about. */
enum class ReadinessFactorKind(val label: String) {
    DIASTOLIC("Diastolic"),
    HEART_RATE("Pulse"),
    HRV("HRV"),
    FATIGUE("Fatigue"),
    GEOMAGNETIC("Geomagnetic"),
    RECOVERY("Recovery"),
    TRAINING_LOAD("Training load"),
    MEDICATION("Medication"),
}

/** One input's effect on the score: [points] < 0 limits, > 0 helps, 0 is informational. */
data class ReadinessFactor(val kind: ReadinessFactorKind, val detail: String, val points: Int) {
    val isLimiting: Boolean get() = points < 0
}

/** The advice card: [action] and its explanation in plain language with the reasons behind it. */
data class Advice(val action: AdviceAction, val text: String, val rationale: String?)

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

/** Everything the Advisor tab, its widget and the MCP tool show. */
data class CardioReadiness(
    val assessment: ReadinessAssessment,
    val forecast: List<ReadinessForecastHour>,
    val vitals: Vitals,
) {
    val nextWalkWindow: ReadinessForecastHour? get() = forecast.firstOrNull { it.walkWindow }
}

fun interface CardioReadinessSource {
    suspend fun load(): CardioReadiness
}
