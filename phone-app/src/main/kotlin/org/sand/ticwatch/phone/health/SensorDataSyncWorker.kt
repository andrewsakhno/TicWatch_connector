package org.sand.ticwatch.phone.health

import org.json.JSONArray
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
    suspend fun onSensorDataPush(payload: ByteArray): Result<Int> = runCatching {
        val samples = parse(payload)
        val allSamples = derivers.fold(samples) { accumulated, deriver -> accumulated + deriver.derive(accumulated) }

        healthRecordWriter.write(allSamples).getOrThrow()
        samples.size
    }

    private fun parse(payload: ByteArray): List<SensorSample> {
        val entries = JSONArray(String(payload, Charsets.UTF_8))
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            SensorSample(
                type = SensorType.valueOf(entry.getString("type")),
                value = entry.getDouble("value"),
                recordedAt = Instant.ofEpochMilli(entry.getLong("recordedAtEpochMs")),
                endedAt = if (entry.has(KEY_ENDED_AT)) Instant.ofEpochMilli(entry.getLong(KEY_ENDED_AT)) else null,
            )
        }
    }

    private companion object {
        /** Present only on interval samples (e.g. sleep sessions). */
        const val KEY_ENDED_AT = "endedAtEpochMs"
    }
}
