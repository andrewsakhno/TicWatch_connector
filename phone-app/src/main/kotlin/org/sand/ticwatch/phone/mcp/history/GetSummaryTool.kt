package org.sand.ticwatch.phone.mcp.history

import org.json.JSONObject
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool

/** One min/max/avg/sum/count aggregate of a sensor type over a range. */
class GetSummaryTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val selector: RollupResolutionSelector,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_summary"
    override val description =
        "Single aggregate (min/max/avg/sum/count) of one sensor type over a time range, e.g. average heart rate " +
            "or total steps for a day. Range edges are rounded out to the bucket used (5 minutes within 90 days, " +
            "otherwise 1 hour or 1 day)."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.TYPE, HistoryToolSchema.typeProperty())
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty()),
        required = listOf(HistoryToolSchema.TYPE),
    )

    override suspend fun call(arguments: JSONObject): String {
        val type = parser.sensorType(arguments)
        val range = parser.range(arguments)
        val resolution = selector.forSummary(range, parser.now())
        val result = JSONObject()
            .put("type", type.name)
            .put("range", format.range(range))
            .put("resolution", format.resolution(resolution))
        val summary = queries.summary(type, resolution, range.from, range.to)
        return (if (summary == null) result.put("count", 0) else result.put("summary", format.aggregate(summary))).toString()
    }
}
