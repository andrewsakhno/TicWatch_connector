package org.sand.ticwatch.phone.spaceweather

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * JSON of [SpaceWeather], both the phone's stored copy and the `/insights/space-weather` payload; mirrors
 * watch-app's `spaceweather/SpaceWeatherPayload.kt` (no shared module, keep the keys in sync). Level labels travel
 * as text so the watch needs none of the enums; the decoder ignores them.
 */
object SpaceWeatherPayload {
    const val FETCHED_AT = "fetched_at"
    const val CURRENT_KP = "current_kp"
    const val CURRENT_AT = "current_at"
    const val CURRENT_ORIGIN = "current_origin"
    const val CURRENT_LEVEL = "current_level"
    const val DAYS = "days"
    const val DATE = "date"
    const val MAX_KP = "max_kp"
    const val LEVEL = "level"
    const val RADIO_FLUX = "radio_flux"
    const val SOLAR_ACTIVITY = "solar_activity"
    const val FORECAST = "forecast"
    const val SLOTS = "slots"
    const val START = "start"
    const val KP = "kp"
    const val ORIGIN = "origin"

    fun encode(weather: SpaceWeather): String {
        val json = JSONObject().put(FETCHED_AT, weather.fetchedAt.toEpochMilli())
        weather.current?.let {
            json.put(CURRENT_KP, it.kp)
                .put(CURRENT_AT, it.start.toEpochMilli())
                .put(CURRENT_ORIGIN, it.origin.noaaName)
                .put(CURRENT_LEVEL, GeomagneticLevel.of(it.kp).label)
        }
        json.put(DAYS, JSONArray().apply { weather.days.forEach { put(day(it)) } })
        json.put(SLOTS, JSONArray().apply { weather.slots.forEach { put(slot(it)) } })
        return json.toString()
    }

    fun decode(text: String): SpaceWeather {
        val json = JSONObject(text)
        val current = if (json.has(CURRENT_KP)) {
            KpOrigin.of(json.getString(CURRENT_ORIGIN))?.let {
                KpSlot(Instant.ofEpochMilli(json.getLong(CURRENT_AT)), json.getDouble(CURRENT_KP), it)
            }
        } else {
            null
        }
        val days = json.getJSONArray(DAYS).objects().map {
            SpaceWeatherDay(
                date = LocalDate.parse(it.getString(DATE)),
                maxKp = it.getDouble(MAX_KP),
                radioFlux = if (it.has(RADIO_FLUX)) it.getInt(RADIO_FLUX) else null,
                forecast = it.getBoolean(FORECAST),
            )
        }
        val slots = json.optJSONArray(SLOTS)?.objects().orEmpty().mapNotNull { slot ->
            KpOrigin.of(slot.getString(ORIGIN))?.let { KpSlot(Instant.ofEpochMilli(slot.getLong(START)), slot.getDouble(KP), it) }
        }
        return SpaceWeather(Instant.ofEpochMilli(json.getLong(FETCHED_AT)), current, days, slots)
    }

    private fun day(day: SpaceWeatherDay): JSONObject {
        val json = JSONObject()
            .put(DATE, day.date.toString())
            .put(MAX_KP, day.maxKp)
            .put(LEVEL, day.level.label)
            .put(FORECAST, day.forecast)
        day.radioFlux?.let { json.put(RADIO_FLUX, it) }
        day.solarActivity?.let { json.put(SOLAR_ACTIVITY, it.label) }
        return json
    }

    private fun slot(slot: KpSlot): JSONObject =
        JSONObject().put(START, slot.start.toEpochMilli()).put(KP, slot.kp).put(ORIGIN, slot.origin.noaaName)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
