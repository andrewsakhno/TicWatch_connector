package org.sand.ticwatch.watch.spaceweather

import org.json.JSONObject

/** One local day of the phone's space weather; [level] and [solarActivity] are the phone's labels. */
data class SpaceWeatherDaySnapshot(
    val date: String,
    val maxKp: Double,
    val level: String,
    val radioFlux: Int?,
    val solarActivity: String?,
    val forecast: Boolean,
)

/** NOAA Kp of the current 3-hour slot (null if the phone had none) and today + the forecast days. */
data class SpaceWeatherSnapshot(
    val fetchedAtEpochMs: Long,
    val currentKp: Double?,
    val currentLevel: String?,
    val days: List<SpaceWeatherDaySnapshot>,
)

/**
 * Decodes the phone's `/insights/space-weather` JSON; mirrors phone-app's `spaceweather/SpaceWeatherPayload.kt`.
 * The phone's 3-hour `slots` array is not needed here and ignored.
 */
object SpaceWeatherPayload {
    private const val FETCHED_AT = "fetched_at"
    private const val CURRENT_KP = "current_kp"
    private const val CURRENT_LEVEL = "current_level"
    private const val DAYS = "days"
    private const val DATE = "date"
    private const val MAX_KP = "max_kp"
    private const val LEVEL = "level"
    private const val RADIO_FLUX = "radio_flux"
    private const val SOLAR_ACTIVITY = "solar_activity"
    private const val FORECAST = "forecast"

    fun decode(text: String): SpaceWeatherSnapshot {
        val json = JSONObject(text)
        val days = json.getJSONArray(DAYS)
        return SpaceWeatherSnapshot(
            fetchedAtEpochMs = json.getLong(FETCHED_AT),
            currentKp = if (json.has(CURRENT_KP)) json.getDouble(CURRENT_KP) else null,
            currentLevel = json.optString(CURRENT_LEVEL).ifEmpty { null },
            days = (0 until days.length()).map { index ->
                val day = days.getJSONObject(index)
                SpaceWeatherDaySnapshot(
                    date = day.getString(DATE),
                    maxKp = day.getDouble(MAX_KP),
                    level = day.getString(LEVEL),
                    radioFlux = if (day.has(RADIO_FLUX)) day.getInt(RADIO_FLUX) else null,
                    solarActivity = day.optString(SOLAR_ACTIVITY).ifEmpty { null },
                    forecast = day.getBoolean(FORECAST),
                )
            },
        )
    }
}
