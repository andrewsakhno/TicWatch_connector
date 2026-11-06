package org.sand.ticwatch.phone.ecg

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Outcome of a watch rhythm recording; wire contract with watch-app's `EcgCaptureStatus` (matched by name). */
enum class EcgCaptureStatus {
    COMPLETED,
    NOT_WORN,
    NO_BEATS,
    BUSY,
    DECLINED,
}

/** One heartbeat: [offsetMs] from the strip start (running sum of intervals), the interval ending at it, its quality. */
data class EcgBeat(val offsetMs: Long, val rrMs: Double, val clean: Boolean)

/** One raw PPG reading of the pulsatile channel, [offsetMs] from the strip start (watch clock, not the RR sum). */
data class EcgWaveSample(val offsetMs: Long, val value: Long)

/** Why a strip was recorded; [code] is stored in `ecg_recording.kind`, [wireName] goes to the cloud payload. */
enum class EcgRecordingKind(val code: Int, val wireName: String) {
    /** Asked for on the phone or after a cuff reading. */
    REQUESTED(0, "requested"),

    /** Recorded by the watch at a sleep blood pressure measurement, for the pulse-wave estimate. */
    NIGHT_PULSE(1, "night_pulse");

    companion object {
        fun of(code: Int): EcgRecordingKind = entries.firstOrNull { it.code == code } ?: REQUESTED
    }
}

/** A recorded rhythm strip; [id] is 0 until stored. */
data class EcgRecording(
    val id: Long,
    val startedAt: Instant,
    val finishedAt: Instant,
    val beats: List<EcgBeat>,
    /** The pulse wave recorded with the beats; empty for strips recorded before it existed. */
    val wave: List<EcgWaveSample> = emptyList(),
    val kind: EcgRecordingKind = EcgRecordingKind.REQUESTED,
) {
    val durationMs: Long get() = maxOf(beats.lastOrNull()?.offsetMs ?: 0L, wave.lastOrNull()?.offsetMs ?: 0L)
}

/** The watch's answer to a recording request. */
data class EcgCaptureResult(
    val status: EcgCaptureStatus,
    val message: String,
    val recording: EcgRecording,
    val requestId: String?,
)

/** One `/ecg/progress` message. */
data class EcgProgress(val requestId: String?, val beats: List<EcgBeat>, val wave: List<EcgWaveSample>)

/** `/ecg/progress` and `/ecg/result` payloads, encoded by watch-app's `EcgPayloads`; beats also persist in this form. */
object EcgPayloads {
    const val REQUEST_ID = "request_id"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val STARTED_AT = "started_at"
    const val FINISHED_AT = "finished_at"
    const val BEATS = "beats"
    const val OFFSET_MS = "t"
    const val RR_MS = "rr"
    const val CLEAN = "ok"
    const val WAVE = "wave"
    const val WAVE_OFFSETS = "t"
    const val WAVE_VALUES = "v"

    fun encodeRequest(requestId: String): ByteArray = JSONObject().put(REQUEST_ID, requestId).toString().toByteArray(Charsets.UTF_8)

    fun decodeResult(text: String): EcgCaptureResult {
        val json = JSONObject(text)
        return EcgCaptureResult(
            status = EcgCaptureStatus.valueOf(json.getString(STATUS)),
            message = json.optString(MESSAGE),
            recording = EcgRecording(
                id = 0,
                startedAt = Instant.ofEpochMilli(json.getLong(STARTED_AT)),
                finishedAt = Instant.ofEpochMilli(json.getLong(FINISHED_AT)),
                beats = decodeBeats(json.getJSONArray(BEATS)),
                wave = json.optJSONObject(WAVE)?.let(::decodeWave).orEmpty(),
            ),
            requestId = json.requestId(),
        )
    }

    /** The beats and wave samples recorded since the previous progress message. */
    fun decodeProgress(text: String): EcgProgress {
        val json = JSONObject(text)
        return EcgProgress(json.requestId(), decodeBeats(json.getJSONArray(BEATS)), json.optJSONObject(WAVE)?.let(::decodeWave).orEmpty())
    }

    fun encodeWave(wave: List<EcgWaveSample>): String = JSONObject()
        .put(WAVE_OFFSETS, JSONArray(wave.map { it.offsetMs }))
        .put(WAVE_VALUES, JSONArray(wave.map { it.value }))
        .toString()

    fun decodeWave(text: String): List<EcgWaveSample> = if (text.isEmpty()) emptyList() else decodeWave(JSONObject(text))

    private fun decodeWave(json: JSONObject): List<EcgWaveSample> {
        val offsets = json.getJSONArray(WAVE_OFFSETS)
        val values = json.getJSONArray(WAVE_VALUES)
        return (0 until minOf(offsets.length(), values.length())).map { EcgWaveSample(offsets.getLong(it), values.getLong(it)) }
    }

    fun encodeBeats(beats: List<EcgBeat>): String = JSONArray().apply {
        beats.forEach { put(JSONObject().put(OFFSET_MS, it.offsetMs).put(RR_MS, it.rrMs).put(CLEAN, it.clean)) }
    }.toString()

    fun decodeBeats(text: String): List<EcgBeat> = decodeBeats(JSONArray(text))

    private fun decodeBeats(array: JSONArray): List<EcgBeat> = (0 until array.length()).map { index ->
        val beat = array.getJSONObject(index)
        EcgBeat(beat.getLong(OFFSET_MS), beat.getDouble(RR_MS), beat.getBoolean(CLEAN))
    }

    private fun JSONObject.requestId(): String? = optString(REQUEST_ID).takeIf { it.isNotEmpty() }
}

/** User-selectable cardiogram retention, chosen in Settings. */
enum class EcgRetention(val days: Long) {
    THREE_DAYS(3),
    NINE_DAYS(9),
    EIGHTEEN_DAYS(18),
    THIRTY_DAYS(30);

    companion object {
        val DEFAULT = THIRTY_DAYS
    }
}
