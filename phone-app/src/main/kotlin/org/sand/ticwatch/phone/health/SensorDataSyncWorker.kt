package org.sand.ticwatch.phone.health

import org.json.JSONArray
import java.time.Instant

/**
 * Handles a single sensor-data push received from the watch: parses the wire payload,
 * derives resting heart rate from the live heart-rate samples, and writes everything
 * into Health Connect via [healthRecordWriter].
 */
class SensorDataSyncWorker(
    private val healthRecordWriter: HealthRecordWriter,
    private val restingHeartRateEstimator: RestingHeartRateEstimator,
) {
    suspend fun onSensorDataPush(payload: ByteArray): Result<Int> = runCatching {
        val samples = parse(payload)
        val restingHeartRateSamples = samples
            .filter { it.type == SensorType.HEART_RATE }
            .map { restingHeartRateEstimator.onHeartRateSample(it) }

        healthRecordWriter.write(samples + restingHeartRateSamples).getOrThrow()
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
            )
        }
    }
}
