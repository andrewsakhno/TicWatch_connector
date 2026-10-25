package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool

/** Time series of one sensor type, aggregated to the finest tier that fits the requested point budget. */
class GetSeriesTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val selector: RollupResolutionSelector,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_series"
    override val description =
        "Time series of one sensor type as buckets (5 minutes, 1 hour or 1 day - the finest that keeps the result " +
            "within max_points and is still retained: 5-minute buckets for 90 days, hourly for 365, daily forever). " +
            "Each point has min/max/avg/sum/count. Use 'sum' for cumulative types (steps, calories, distance, floors, " +
            "elevation gain) and avg/min/max for instantaneous ones (heart rate, SpO2, HRV, " +
            "temperature, respiration rate, barometric pressure, ambient light). " +
            "Pass 'resolution' to force a bucket size; a range longer than max_points buckets is cut and the response " +
            "carries 'next_from' - call again with it as 'from' to page through the full history."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.TYPE, HistoryToolSchema.typeProperty())
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(
                HistoryToolSchema.MAX_POINTS,
                HistoryToolSchema.intProperty("Upper bound on returned buckets", DEFAULT_MAX_POINTS, MAX_POINTS),
            )
            .put(HistoryToolSchema.RESOLUTION, HistoryToolSchema.resolutionProperty()),
        required = listOf(HistoryToolSchema.TYPE),
    )

    override suspend fun call(arguments: JSONObject): String {
        val type = parser.sensorType(arguments)
        val range = parser.range(arguments)
        val maxPoints = parser.boundedInt(arguments, HistoryToolSchema.MAX_POINTS, DEFAULT_MAX_POINTS, MAX_POINTS)
        val page = selector.page(range, maxPoints, parser.resolution(arguments), parser.now())

        val points = JSONArray()
        queries.rollups(type, page.resolution, page.window.from, page.window.to).forEach { point ->
            points.put(format.aggregate(point).put("t", format.time(point.bucketStart)))
        }
        val result = JSONObject()
            .put("type", type.name)
            .put("range", format.range(page.window))
            .put("resolution", format.resolution(page.resolution))
            .put("points", points)
        page.nextFrom?.let { result.put("next_from", format.time(it)) }
        return result.toString()
    }

    private companion object {
        const val DEFAULT_MAX_POINTS = 300
        const val MAX_POINTS = 1000
    }
}
