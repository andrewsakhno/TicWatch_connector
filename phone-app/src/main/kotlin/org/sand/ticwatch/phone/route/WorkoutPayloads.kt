package org.sand.ticwatch.phone.route

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Wire contract with watch-app's `WorkoutPayloads` (keep in sync manually):
 * - workout state: `{"active": true|false}`
 * - route push: `[{"recordedAtEpochMs", "latitude", "longitude", "altitudeM"?, "accuracyM"?}, ...]`
 */
object WorkoutPayloads {
    const val ACTIVE = "active"
    const val RECORDED_AT = "recordedAtEpochMs"
    const val LATITUDE = "latitude"
    const val LONGITUDE = "longitude"
    const val ALTITUDE = "altitudeM"
    const val ACCURACY = "accuracyM"

    fun isWorkoutActive(payload: ByteArray): Boolean = JSONObject(String(payload, Charsets.UTF_8)).getBoolean(ACTIVE)

    /** Points the watch recorded on its own GPS; throws on a malformed payload so the watch retries it. */
    fun watchRoutePoints(payload: ByteArray): List<RoutePoint> {
        val entries = JSONArray(String(payload, Charsets.UTF_8))
        return (0 until entries.length()).map { index ->
            val entry = entries.getJSONObject(index)
            RoutePoint(
                recordedAt = Instant.ofEpochMilli(entry.getLong(RECORDED_AT)),
                latitude = entry.getDouble(LATITUDE),
                longitude = entry.getDouble(LONGITUDE),
                altitudeMeters = entry.optDoubleOrNull(ALTITUDE),
                horizontalAccuracyMeters = entry.optDoubleOrNull(ACCURACY),
                source = RouteSource.WATCH,
            )
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else getDouble(key)
}
