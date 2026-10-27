package org.sand.ticwatch.phone.datalayer

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.HeartRateReading
import org.sand.ticwatch.phone.health.SampleSource
import java.time.Instant
import kotlin.math.roundToInt

/**
 * JSON of the blood pressure messages exchanged with the watch; mirrors watch-app's
 * `bloodpressure/BloodPressurePayloads.kt` (no shared module, keep the keys in sync). Labels travel as text so the
 * watch needs none of the enums.
 */
object BloodPressureWatchPayloads {
    const val READINGS = "readings"
    const val SYSTOLIC = "systolic"
    const val DIASTOLIC = "diastolic"
    const val PULSE = "pulse"
    const val AT = "at"
    const val SOURCE = "source"
    const val CATEGORY = "category"
    const val CUFF = "cuff"
    const val SUCCESS = "success"
    const val STATUS = "status"
    const val HEART_RATE = "heart_rate"
    const val HEART_RATE_AT = "heart_rate_at"

    fun encodeHistory(readings: List<BloodPressureReading>): String {
        val entries = JSONArray()
        readings.forEach { reading ->
            val entry = JSONObject()
                .put(SYSTOLIC, reading.systolic.roundToInt())
                .put(DIASTOLIC, reading.diastolic.roundToInt())
                .put(AT, reading.measuredAt.toEpochMilli())
                .put(SOURCE, reading.source.label)
                .put(CATEGORY, reading.category.label)
                .put(CUFF, reading.source == SampleSource.BLOOD_PRESSURE_CUFF)
            reading.pulse?.let { entry.put(PULSE, it.roundToInt()) }
            entries.put(entry)
        }
        return JSONObject().put(READINGS, entries).toString()
    }

    fun encodeResult(success: Boolean, status: String, at: Instant): String =
        JSONObject().put(SUCCESS, success).put(STATUS, status).put(AT, at.toEpochMilli()).toString()

    /** The watch's on-screen heart rate sent with a measure request, null when it had none. */
    fun decodeLiveHeartRate(payload: ByteArray): HeartRateReading? {
        val text = String(payload, Charsets.UTF_8)
        if (text.isBlank()) return null
        val json = JSONObject(text)
        if (!json.has(HEART_RATE) || !json.has(HEART_RATE_AT)) return null
        return HeartRateReading(json.getDouble(HEART_RATE), Instant.ofEpochMilli(json.getLong(HEART_RATE_AT)))
    }
}
