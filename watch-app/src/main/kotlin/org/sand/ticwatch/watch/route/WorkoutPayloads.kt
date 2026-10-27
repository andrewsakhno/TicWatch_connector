package org.sand.ticwatch.watch.route

import org.json.JSONArray
import org.json.JSONObject

/** One GPS fix the watch recorded during a workout. */
data class RouteFix(
    val recordedAtEpochMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val horizontalAccuracyMeters: Double?,
)

/**
 * Wire contract with phone-app's `WorkoutPayloads` (keep in sync manually):
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

    fun workoutState(active: Boolean): ByteArray = JSONObject().put(ACTIVE, active).toString().toByteArray(Charsets.UTF_8)

    fun routePush(fixes: List<RouteFix>): ByteArray {
        val entries = JSONArray()
        fixes.forEach { fix ->
            entries.put(
                JSONObject()
                    .put(RECORDED_AT, fix.recordedAtEpochMs)
                    .put(LATITUDE, fix.latitude)
                    .put(LONGITUDE, fix.longitude)
                    .put(ALTITUDE, fix.altitudeMeters ?: JSONObject.NULL)
                    .put(ACCURACY, fix.horizontalAccuracyMeters ?: JSONObject.NULL),
            )
        }
        return entries.toString().toByteArray(Charsets.UTF_8)
    }
}
