package org.sand.ticwatch.watch.scores

import org.json.JSONObject

/** The phone's derived scores of [date]; labels come computed from the phone, null where it had no data. */
data class HealthScoresSnapshot(
    val date: String,
    val sleepScore: Int?,
    val sleepQuality: String?,
    val hrvStatus: String?,
    val hrvWeeklyMs: Double?,
    val hrvLowMs: Double?,
    val hrvHighMs: Double?,
    val loadAcute: Double,
    val loadRatio: Double?,
    val loadBalance: String?,
    val stressShares: List<Pair<String, Double>>,
    val lightMinutes: Long,
    val lightGoalMinutes: Long,
)

/** Decodes the phone's `/insights/scores` JSON; mirrors phone-app's `insights/HealthScoresPayload.kt`. */
object HealthScoresPayload {
    private const val DATE = "date"
    private const val SLEEP_SCORE = "sleep_score"
    private const val SLEEP_QUALITY = "sleep_quality"
    private const val HRV_STATUS = "hrv_status"
    private const val HRV_WEEKLY_MS = "hrv_weekly_ms"
    private const val HRV_LOW_MS = "hrv_low_ms"
    private const val HRV_HIGH_MS = "hrv_high_ms"
    private const val LOAD_ACUTE = "load_acute"
    private const val LOAD_RATIO = "load_ratio"
    private const val LOAD_BALANCE = "load_balance"
    private const val STRESS_PREFIX = "stress_"
    private const val LIGHT_MINUTES = "light_minutes"
    private const val LIGHT_GOAL_MINUTES = "light_goal_minutes"

    /** Stress levels in the phone's order, as `stress_<level>` keys, with their display labels. */
    private val STRESS_LEVELS = listOf("rest" to "Rest", "low" to "Low", "moderate" to "Medium", "high" to "High")

    fun decode(text: String): HealthScoresSnapshot {
        val json = JSONObject(text)
        return HealthScoresSnapshot(
            date = json.getString(DATE),
            sleepScore = if (json.has(SLEEP_SCORE)) json.getInt(SLEEP_SCORE) else null,
            sleepQuality = json.optString(SLEEP_QUALITY).ifEmpty { null },
            hrvStatus = json.optString(HRV_STATUS).ifEmpty { null },
            hrvWeeklyMs = json.optDoubleOrNull(HRV_WEEKLY_MS),
            hrvLowMs = json.optDoubleOrNull(HRV_LOW_MS),
            hrvHighMs = json.optDoubleOrNull(HRV_HIGH_MS),
            loadAcute = json.optDouble(LOAD_ACUTE, 0.0),
            loadRatio = json.optDoubleOrNull(LOAD_RATIO),
            loadBalance = json.optString(LOAD_BALANCE).ifEmpty { null },
            stressShares = STRESS_LEVELS.mapNotNull { (key, label) -> json.optDoubleOrNull(STRESS_PREFIX + key)?.let { label to it } },
            lightMinutes = json.optLong(LIGHT_MINUTES, 0L),
            lightGoalMinutes = json.optLong(LIGHT_GOAL_MINUTES, 0L),
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) getDouble(key) else null
}
