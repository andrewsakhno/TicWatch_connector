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

/** The phone's cardio readiness at [atEpochMs]: the score and the latest pressure / pulse, null without data. */
data class CardioReadinessSnapshot(
    val score: LeveledValue,
    val pressure: LeveledValue?,
    val heartRate: LeveledValue?,
    val atEpochMs: Long,
)

/** Decodes the phone's `/insights/readiness` JSON; mirrors phone-app's `advisor/CardioReadinessPayload.kt`. */
object CardioReadinessPayload {
    private const val SCORE = "score"
    private const val SCORE_LEVEL = "score_level"
    private const val SYSTOLIC = "sys"
    private const val DIASTOLIC = "dia"
    private const val PRESSURE_LEVEL = "bp_level"
    private const val HEART_RATE = "hr"
    private const val HEART_RATE_LEVEL = "hr_level"
    private const val AT = "at"

    fun decode(text: String): CardioReadinessSnapshot {
        val json = JSONObject(text)
        return CardioReadinessSnapshot(
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
            atEpochMs = json.getLong(AT),
        )
    }
}
