package org.sand.ticwatch.watch.readiness

import org.json.JSONObject

/** How concerning a value is, as the phone classified it; [code] mirrors phone-app's `advisor/VitalLevel`. */
enum class VitalLevel(val code: Int, val argb: Int) {
    GOOD(0, 0xFF43A047.toInt()),
    FAIR(1, 0xFFFDD835.toInt()),
    ELEVATED(2, 0xFFFB8C00.toInt()),
    HIGH(3, 0xFFE53935.toInt());

    companion object {
        fun ofCode(code: Int): VitalLevel = entries.firstOrNull { it.code == code } ?: GOOD
    }
}

/** A value with its level; [text] is what the tile shows. */
data class LeveledValue(val text: String, val level: VitalLevel)

/** Colour of [value] on screen: by its level, [muted] when it is stale or missing. */
fun readinessValueColor(value: LeveledValue?, stale: Boolean, muted: Int): Int =
    if (value == null || stale) muted else value.level.argb

/**
 * The phone's cardio readiness at [atEpochMs]: the score and the latest pressure / pulse, null without data.
 * [loadReadiness] is the inverse of the phone's fatigue index, null with too few signals.
 * [autonomicPercent] is the nervous tone (100 = calm), [postStress] true while the phone sees an active post-stress window.
 * [stateLabel], [advice], [fatigueLabel] and [autonomicLabel] are already in the app language; null from a phone app that predates them.
 */
data class CardioReadinessSnapshot(
    val scorePercent: Int,
    val score: LeveledValue,
    val pressure: LeveledValue?,
    val heartRate: LeveledValue?,
    val loadReadinessPercent: Int?,
    val loadReadiness: LeveledValue?,
    val stateLabel: String?,
    val advice: String?,
    val fatigueLabel: String?,
    val autonomicPercent: Int? = null,
    val autonomic: LeveledValue? = null,
    val autonomicLabel: String? = null,
    val postStress: Boolean = false,
    val atEpochMs: Long,
) {
    fun isStale(nowEpochMs: Long): Boolean = nowEpochMs - atEpochMs > STALE_AFTER_MS

    private companion object {
        /** The phone re-sends after every sensor sync (30 min); older than this means the link is down. */
        const val STALE_AFTER_MS = 3 * 60 * 60 * 1_000L
    }
}

/** Decodes the phone's `/insights/readiness` JSON; mirrors phone-app's `advisor/CardioReadinessPayload.kt`. */
object CardioReadinessPayload {
    private const val SCORE = "score"
    private const val SCORE_LEVEL = "score_level"
    private const val SYSTOLIC = "sys"
    private const val DIASTOLIC = "dia"
    private const val PRESSURE_LEVEL = "bp_level"
    private const val HEART_RATE = "hr"
    private const val HEART_RATE_LEVEL = "hr_level"
    private const val STATE_LABEL = "state_label"
    private const val ADVICE = "advice"
    private const val LOAD_READINESS = "load_readiness"
    private const val LOAD_LEVEL = "load_level"
    private const val FATIGUE_LABEL = "fatigue_label"
    private const val AUTONOMIC_READINESS = "autonomic_readiness"
    private const val AUTONOMIC_LEVEL = "autonomic_level"
    private const val AUTONOMIC_LABEL = "autonomic_label"
    private const val POST_STRESS = "post_stress"
    private const val AT = "at"

    fun decode(text: String): CardioReadinessSnapshot {
        val json = JSONObject(text)
        val loadReadinessPercent = if (json.has(LOAD_READINESS)) json.getInt(LOAD_READINESS) else null
        val autonomicPercent = if (json.has(AUTONOMIC_READINESS)) json.getInt(AUTONOMIC_READINESS) else null
        return CardioReadinessSnapshot(
            scorePercent = json.getInt(SCORE),
            score = LeveledValue(json.getInt(SCORE).toString(), VitalLevel.ofCode(json.getInt(SCORE_LEVEL))),
            pressure = if (json.has(SYSTOLIC) && json.has(DIASTOLIC)) {
                LeveledValue("${json.getInt(SYSTOLIC)}/${json.getInt(DIASTOLIC)}", VitalLevel.ofCode(json.getInt(PRESSURE_LEVEL)))
            } else {
                null
            },
            heartRate = if (json.has(HEART_RATE)) {
                LeveledValue(json.getInt(HEART_RATE).toString(), VitalLevel.ofCode(json.getInt(HEART_RATE_LEVEL)))
            } else {
                null
            },
            loadReadinessPercent = loadReadinessPercent,
            loadReadiness = loadReadinessPercent?.let { LeveledValue(it.toString(), VitalLevel.ofCode(json.getInt(LOAD_LEVEL))) },
            stateLabel = json.optString(STATE_LABEL).ifEmpty { null },
            advice = json.optString(ADVICE).ifEmpty { null },
            fatigueLabel = json.optString(FATIGUE_LABEL).ifEmpty { null },
            autonomicPercent = autonomicPercent,
            autonomic = autonomicPercent?.let { LeveledValue(it.toString(), VitalLevel.ofCode(json.getInt(AUTONOMIC_LEVEL))) },
            autonomicLabel = json.optString(AUTONOMIC_LABEL).ifEmpty { null },
            postStress = json.optBoolean(POST_STRESS, false),
            atEpochMs = json.getLong(AT),
        )
    }
}
