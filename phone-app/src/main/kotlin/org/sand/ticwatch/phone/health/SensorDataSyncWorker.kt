package org.sand.ticwatch.phone.health

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Handles a single sensor-data push received from the watch: parses the wire payload,
 * runs [derivers] over it in order (each sees what earlier ones derived, e.g. VO2 max
 * builds on resting heart rate), and writes everything into Health Connect via
 * [healthRecordWriter].
 */
class SensorDataSyncWorker(
    private val healthRecordWriter: HealthRecordWriter,
    private val derivers: List<SampleDeriver>,
) {
    suspend fun onSensorDataPush(payload: ByteArray): Result<Int> =
        runCatching { parse(payload) }.fold(onSuccess = { ingest(it) }, onFailure = { Result.failure(it) })

    /** Derives from and writes already-decoded watch samples (e.g. a snapshot's); returns how many came in. */
    suspend fun ingest(samples: List<SensorSample>): Result<Int> = runCatching {
        val allSamples = derivers.fold(samples) { accumulated, deriver -> accumulated + deriver.derive(accumulated) }

        healthRecordWriter.write(allSamples).getOrThrow()
        samples.size
    }

    private fun parse(payload: ByteArray): List<SensorSample> {
        val entries = JSONArray(String(payload, Charsets.UTF_8))
        return (0 until entries.length()).map { index -> SensorSampleJson.decode(entries.getJSONObject(index)) }
    }
}

/** Wire shape of one watch sample (`/sensors/push` entries, snapshot samples), mirrored by watch-app's encoder. */
object SensorSampleJson {
    const val TYPE = "type"
    const val VALUE = "value"
    const val RECORDED_AT = "recordedAtEpochMs"

    /** Present only on interval samples (e.g. sleep sessions). */
    const val ENDED_AT = "endedAtEpochMs"

    fun decode(entry: JSONObject): SensorSample = SensorSample(
        type = SensorType.valueOf(entry.getString(TYPE)),
        value = entry.getDouble(VALUE),
        recordedAt = Instant.ofEpochMilli(entry.getLong(RECORDED_AT)),
        endedAt = if (entry.has(ENDED_AT)) Instant.ofEpochMilli(entry.getLong(ENDED_AT)) else null,
    )
}
