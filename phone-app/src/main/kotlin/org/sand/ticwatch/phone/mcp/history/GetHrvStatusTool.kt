package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.insights.HrvStatus
import org.sand.ticwatch.phone.insights.HrvStatusHistory
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration
import java.time.ZoneId

/** Daily HRV status: the 7-day nightly RMSSD mean against the personal 28-day corridor. */
class GetHrvStatusTool(
    private val history: HrvStatusHistory,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
    private val zone: ZoneId,
) : McpTool {
    override val name = "get_hrv_status"
    override val description =
        "Per local day: HRV status from nightly RMSSD (mean over each sleep session >= 3 h, keyed by the wake-up " +
            "day). weekly_mean_ms is the mean of the last 7 days' nights (status needs >= 3), baseline the last 28 " +
            "days' mean ± 0.75 SD (needs >= 14 nights). status: Balanced (inside), Unbalanced (outside), Low " +
            "(> 1.5 SD below the mean), Poor (weekly mean < 20 ms); null without enough nights. A falling, Low or " +
            "Poor status signals incomplete recovery (strain, illness, alcohol, poor sleep). Uses rollups (~90 " +
            "days back). The last entry is the current status. Default range: the last $DEFAULT_DAYS days."
    override val inputSchema: JSONObject = HistoryToolSchema.dayRangeSchema(DEFAULT_DAYS)

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, Duration.ofDays(DEFAULT_DAYS))
        val days = JSONArray()
        history.statuses(range.from.atZone(zone).toLocalDate(), range.to.minusMillis(1).atZone(zone).toLocalDate())
            .forEach { days.put(day(it)) }
        return JSONObject().put("range", format.range(range)).put("days", days).toString()
    }

    private fun day(status: HrvStatus): JSONObject {
        val baseline = status.baseline?.let {
            JSONObject()
                .put("mean_ms", format.number(it.mean))
                .put("sd_ms", format.number(it.standardDeviation))
                .put("low_ms", format.number(it.low))
                .put("high_ms", format.number(it.high))
                .put("nights", it.nights)
        }
        return JSONObject()
            .put("date", status.date.toString())
            .put("status", status.level?.label ?: JSONObject.NULL)
            .put("weekly_mean_ms", status.weeklyMean?.let(format::number) ?: JSONObject.NULL)
            .put("weekly_nights", status.weeklyNights)
            .put("baseline", baseline ?: JSONObject.NULL)
    }

    private companion object {
        const val DEFAULT_DAYS = 7L
    }
}
