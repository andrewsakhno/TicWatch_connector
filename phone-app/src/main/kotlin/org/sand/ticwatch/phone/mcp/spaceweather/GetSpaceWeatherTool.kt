package org.sand.ticwatch.phone.mcp.spaceweather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.mcp.history.HistoryToolSchema
import org.sand.ticwatch.phone.spaceweather.GeomagneticLevel
import org.sand.ticwatch.phone.spaceweather.KpSlot
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherDay
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import java.time.ZoneId

/** The space weather the phone last fetched from NOAA SWPC (refreshed every 3 h). */
class GetSpaceWeatherTool(
    private val store: SpaceWeatherStore,
    private val zone: ZoneId,
) : McpTool {
    override val name = "get_space_weather"
    override val description =
        "Geomagnetic and solar activity from NOAA SWPC, as last fetched by the phone (every 3 h; fetched_at says " +
            "when). current: the planetary Kp (0-9) of the current 3-hour UTC slot. days: today + up to 5 local days " +
            "with the day's max Kp, NOAA G-scale level (Kp >= 5 is a storm, G1-G5), F10.7 radio flux in sfu " +
            "(solar activity proxy, from the 27-day outlook) and forecast=false only for a fully observed day; days " +
            "4-5 come from the outlook's daily largest Kp only. slots: every 3-hour Kp NOAA returned (about the past " +
            "week observed/estimated plus 3 days predicted), for correlating with health metrics. Not stored as " +
            "history: only the latest fetch is kept."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(JSONObject())

    override suspend fun call(arguments: JSONObject): String {
        val weather = withContext(Dispatchers.IO) { store.load() }
            ?: return JSONObject().put("error", "Space weather has not been fetched yet").toString()
        return JSONObject()
            .put("time_zone", zone.id)
            .put("fetched_at", weather.fetchedAt.atZone(zone).toOffsetDateTime().toString())
            .put("current", weather.current?.let(::slot) ?: JSONObject.NULL)
            .put("days", JSONArray().apply { weather.days.forEach { put(day(it)) } })
            .put("slots", JSONArray().apply { weather.slots.forEach { put(slot(it)) } })
            .toString()
    }

    private fun slot(slot: KpSlot): JSONObject = JSONObject()
        .put("start", slot.start.atZone(zone).toOffsetDateTime().toString())
        .put("kp", slot.kp)
        .put("origin", slot.origin.noaaName)
        .put("level", GeomagneticLevel.of(slot.kp).label)

    private fun day(day: SpaceWeatherDay): JSONObject = JSONObject()
        .put("date", day.date.toString())
        .put("max_kp", day.maxKp)
        .put("level", day.level.label)
        .put("radio_flux_sfu", day.radioFlux ?: JSONObject.NULL)
        .put("solar_activity", day.solarActivity?.label ?: JSONObject.NULL)
        .put("forecast", day.forecast)
}
