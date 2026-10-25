package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.history.NightlySkinTemperature
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration

/** Per-night wrist temperature and its deviation from the personal nightly baseline. */
class GetNightlySkinTemperatureTool(
    private val nightly: NightlySkinTemperature,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_nightly_skin_temperature"
    override val description =
        "Per sleep session: mean wrist skin temperature (°C), the personal baseline (median of up to 14 earlier " +
            "nights, null until 3 exist) and delta = mean - baseline. The nightly delta is the meaningful signal " +
            "for core-temperature shifts (fever, cycle phase); absolute wrist values mostly follow ambient. Uses " +
            "rollups, so it reaches back ~1 year. Default range: the last $DEFAULT_DAYS days, oldest first."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(
                HistoryToolSchema.FROM,
                HistoryToolSchema.fromProperty().put("description", "Range start, inclusive. Default: $DEFAULT_DAYS days before 'to'"),
            )
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty()),
    )

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, DEFAULT_SPAN)
        val rows = JSONArray()
        nightly.nights(range.from, range.to).forEach { night ->
            rows.put(
                JSONObject()
                    .put("start", format.time(night.session.start))
                    .put("end", format.time(night.session.end))
                    .put("mean_skin_c", format.number(night.meanSkin))
                    .put("baseline_c", night.baseline?.let(format::number) ?: JSONObject.NULL)
                    .put("delta_c", night.delta?.let(format::number) ?: JSONObject.NULL)
                    .put("baseline_nights", night.baselineNightCount),
            )
        }
        return JSONObject().put("range", format.range(range)).put("nights", rows).toString()
    }

    private companion object {
        const val DEFAULT_DAYS = 30L
        val DEFAULT_SPAN: Duration = Duration.ofDays(DEFAULT_DAYS)
    }
}
