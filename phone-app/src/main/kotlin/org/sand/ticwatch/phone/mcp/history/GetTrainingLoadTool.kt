package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.HeartRateZone
import org.sand.ticwatch.phone.insights.TrainingLoadBalance
import org.sand.ticwatch.phone.insights.TrainingLoadHistory
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration
import java.time.ZoneId

/** Daily cardio load, acute vs chronic, and time per heart-rate zone. */
class GetTrainingLoadTool(
    private val history: TrainingLoadHistory,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
    private val zone: ZoneId,
) : McpTool {
    override val name = "get_training_load"
    override val description =
        "Per local day: cardio load (Banister TRIMP summed over the day's workouts, watch and auto-detected), " +
            "acute load (7-day sum), chronic load (28-day weekly mean), ratio = acute / chronic (null without " +
            "chronic load) with balance: Detraining < 0.8, Optimal 0.8-1.3, High 1.3-1.5, Overreaching > 1.5; and " +
            "minutes per Karvonen zone (1: 50-60%, 2: 60-70%, 3: 70-80%, 4: 80-90%, 5: >= 90% of HR reserve). " +
            "zone_minutes_total sums the range. Needs the birth year in the profile. Uses day rollups (kept " +
            "forever). The last entry is today. Default range: the last $DEFAULT_DAYS days."
    override val inputSchema: JSONObject = HistoryToolSchema.dayRangeSchema(DEFAULT_DAYS)

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, Duration.ofDays(DEFAULT_DAYS))
        val from = range.from.atZone(zone).toLocalDate()
        val to = range.to.minusMillis(1).atZone(zone).toLocalDate()
        val zonesPerDay = history.dailyZoneMinutes(from, to)
        val days = JSONArray()
        history.balances(from, to).forEach { balance -> days.put(day(balance, zonesPerDay[balance.date].orEmpty())) }
        return JSONObject()
            .put("range", format.range(range))
            .put("days", days)
            .put("zone_minutes_total", zones(history.zoneMinutes(from, to)))
            .toString()
    }

    private fun day(balance: TrainingLoadBalance, zoneMinutes: Map<HeartRateZone, Double>): JSONObject = JSONObject()
        .put("date", balance.date.toString())
        .put("load", format.number(balance.dayLoad))
        .put("acute_load", format.number(balance.acuteLoad))
        .put("chronic_weekly_load", format.number(balance.chronicWeeklyLoad))
        .put("ratio", balance.ratio?.let(format::number) ?: JSONObject.NULL)
        .put("balance", balance.balance?.label ?: JSONObject.NULL)
        .put("zone_minutes", zones(zoneMinutes))

    private fun zones(minutes: Map<HeartRateZone, Double>): JSONObject = JSONObject().also { json ->
        HeartRateZone.entries.forEach { zone -> json.put("zone_${zone.number}", format.number(minutes[zone] ?: 0.0)) }
    }

    private companion object {
        const val DEFAULT_DAYS = 7L
    }
}
