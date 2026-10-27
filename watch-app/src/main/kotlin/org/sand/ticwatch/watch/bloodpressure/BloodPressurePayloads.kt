package org.sand.ticwatch.watch.bloodpressure

import org.json.JSONObject
import java.time.Instant

/** One saved reading as the phone describes it; [source] and [category] are display labels. */
data class BloodPressureEntry(
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int?,
    val measuredAt: Instant,
    val source: String,
    val category: String,
    /** A cuff measurement, as opposed to the wrist estimate. */
    val fromCuff: Boolean,
)

/** The phone's latest saved readings, newest first. */
data class BloodPressureHistory(val readings: List<BloodPressureEntry>)

/** Outcome of the last measure request: [status] is a display line, [success] whether a reading was saved. */
data class BloodPressureMeasureResult(val success: Boolean, val status: String, val at: Instant)

/** Blood pressure messages exchanged with the phone; mirrors phone-app's `datalayer/BloodPressureWatchPayloads.kt`. */
object BloodPressurePayloads {
    private const val READINGS = "readings"
    private const val SYSTOLIC = "systolic"
    private const val DIASTOLIC = "diastolic"
    private const val PULSE = "pulse"
    private const val AT = "at"
    private const val SOURCE = "source"
    private const val CATEGORY = "category"
    private const val CUFF = "cuff"
    private const val SUCCESS = "success"
    private const val STATUS = "status"
    private const val HEART_RATE = "heart_rate"
    private const val HEART_RATE_AT = "heart_rate_at"

    fun decodeHistory(text: String): BloodPressureHistory {
        val entries = JSONObject(text).getJSONArray(READINGS)
        return BloodPressureHistory(
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                BloodPressureEntry(
                    systolic = entry.getInt(SYSTOLIC),
                    diastolic = entry.getInt(DIASTOLIC),
                    pulse = if (entry.has(PULSE)) entry.getInt(PULSE) else null,
                    measuredAt = Instant.ofEpochMilli(entry.getLong(AT)),
                    source = entry.optString(SOURCE),
                    category = entry.optString(CATEGORY),
                    fromCuff = entry.optBoolean(CUFF),
                )
            },
        )
    }

    fun decodeResult(text: String): BloodPressureMeasureResult {
        val json = JSONObject(text)
        return BloodPressureMeasureResult(json.getBoolean(SUCCESS), json.getString(STATUS), Instant.ofEpochMilli(json.getLong(AT)))
    }

    /** A result the watch records itself, e.g. when the request never reached the phone. */
    fun encodeResult(result: BloodPressureMeasureResult): String =
        JSONObject().put(SUCCESS, result.success).put(STATUS, result.status).put(AT, result.at.toEpochMilli()).toString()

    /** Measure request; carries the on-screen heart rate when there is one. */
    fun encodeRequest(heartRate: Double?, heartRateAt: Instant?): String {
        val json = JSONObject()
        if (heartRate != null && heartRateAt != null) json.put(HEART_RATE, heartRate).put(HEART_RATE_AT, heartRateAt.toEpochMilli())
        return json.toString()
    }
}
