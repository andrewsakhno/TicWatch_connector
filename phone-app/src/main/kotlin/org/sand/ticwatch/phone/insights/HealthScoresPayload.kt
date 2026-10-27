package org.sand.ticwatch.phone.insights

import org.json.JSONObject

/**
 * JSON of [HealthScores] for the watch's dashboard; mirrors watch-app's `scores/HealthScoresPayload.kt`
 * (no shared module, keep the keys in sync). Labels travel as text so the watch needs none of the enums.
 */
object HealthScoresPayload {
    const val DATE = "date"
    const val SLEEP_SCORE = "sleep_score"
    const val SLEEP_QUALITY = "sleep_quality"
    const val HRV_STATUS = "hrv_status"
    const val HRV_WEEKLY_MS = "hrv_weekly_ms"
    const val HRV_LOW_MS = "hrv_low_ms"
    const val HRV_HIGH_MS = "hrv_high_ms"
    const val LOAD_ACUTE = "load_acute"
    const val LOAD_RATIO = "load_ratio"
    const val LOAD_BALANCE = "load_balance"
    const val STRESS_PREFIX = "stress_"
    const val LIGHT_MINUTES = "light_minutes"
    const val LIGHT_GOAL_MINUTES = "light_goal_minutes"

    fun encode(scores: HealthScores): String {
        val json = JSONObject()
            .put(DATE, scores.date.toString())
            .put(LOAD_ACUTE, scores.trainingLoad.acuteLoad)
            .put(LIGHT_MINUTES, scores.morningLight.bright.toMinutes())
            .put(LIGHT_GOAL_MINUTES, scores.morningLight.goal.toMinutes())
        scores.sleepScore?.let { json.put(SLEEP_SCORE, it.score).put(SLEEP_QUALITY, SleepQuality.of(it.score).label) }
        scores.hrvStatus.level?.let { json.put(HRV_STATUS, it.label) }
        scores.hrvStatus.weeklyMean?.let { json.put(HRV_WEEKLY_MS, it) }
        scores.hrvStatus.baseline?.let { json.put(HRV_LOW_MS, it.low).put(HRV_HIGH_MS, it.high) }
        scores.trainingLoad.ratio?.let { json.put(LOAD_RATIO, it) }
        scores.trainingLoad.balance?.let { json.put(LOAD_BALANCE, it.label) }
        scores.stress?.let { stress -> StressLevel.entries.forEach { json.put(stressKey(it), stress.share(it)) } }
        return json.toString()
    }

    /** One key per level, e.g. `stress_rest`; the watch shows them in this order. */
    fun stressKey(level: StressLevel): String = STRESS_PREFIX + level.name.lowercase()
}
