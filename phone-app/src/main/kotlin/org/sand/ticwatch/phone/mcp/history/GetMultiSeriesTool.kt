package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Instant

/** Several sensor types on one shared bucket grid, as a table ready for correlation and forecasting. */
class GetMultiSeriesTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val selector: RollupResolutionSelector,
    private val catalog: SensorMetricCatalog,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_multi_series"
    override val description =
        "Aligned time series of several sensor types on the same buckets, as a table: one row per bucket, one " +
            "column per type (null where a type has no data). Each column holds the type's natural statistic " +
            "(sum for cumulative, avg for instantaneous, max for running totals) unless 'stat' overrides it. " +
            "Use it to correlate metrics (e.g. steps vs. resting HR vs. HRV per day) and as forecasting input. " +
            "Bucket choice and 'next_from' paging work as in get_series."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(
                HistoryToolSchema.TYPES,
                HistoryToolSchema.typesProperty("Sensor types, one column each (up to $MAX_TYPES)")
                    .put("minItems", 1).put("maxItems", MAX_TYPES),
            )
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(
                HistoryToolSchema.MAX_POINTS,
                HistoryToolSchema.intProperty("Upper bound on returned rows", DEFAULT_MAX_POINTS, MAX_POINTS),
            )
            .put(HistoryToolSchema.RESOLUTION, HistoryToolSchema.resolutionProperty())
            .put(HistoryToolSchema.STAT, HistoryToolSchema.statProperty()),
        required = listOf(HistoryToolSchema.TYPES),
    )

    override suspend fun call(arguments: JSONObject): String {
        require((arguments.optJSONArray(HistoryToolSchema.TYPES)?.length() ?: 0) in 1..MAX_TYPES) {
            "'${HistoryToolSchema.TYPES}' must list 1 to $MAX_TYPES sensor types"
        }
        val types = parser.sensorTypes(arguments).toList()
        val range = parser.range(arguments)
        val maxPoints = parser.boundedInt(arguments, HistoryToolSchema.MAX_POINTS, DEFAULT_MAX_POINTS, MAX_POINTS)
        val forcedStat = parser.stat(arguments)
        val page = selector.page(range, maxPoints, parser.resolution(arguments), parser.now())

        val columns = types.map { type ->
            val stat = forcedStat ?: catalog.describe(type).kind.defaultStat
            val values = queries.rollups(type, page.resolution, page.window.from, page.window.to)
                .associate { point -> point.bucketStart to stat.of(point) }
            Column("${type.name}.${stat.argName}", values)
        }
        val rows = JSONArray()
        columns.flatMapTo(sortedSetOf<Instant>()) { it.values.keys }.forEach { bucket ->
            val row = JSONArray().put(format.time(bucket))
            columns.forEach { column -> row.put(column.values[bucket]?.let(format::number) ?: JSONObject.NULL) }
            rows.put(row)
        }

        val result = JSONObject()
            .put("range", format.range(page.window))
            .put("resolution", format.resolution(page.resolution))
            .put("columns", JSONArray(listOf("t") + columns.map { it.name }))
            .put("rows", rows)
        page.nextFrom?.let { result.put("next_from", format.time(it)) }
        return result.toString()
    }

    private class Column(val name: String, val values: Map<Instant, Double>)

    private companion object {
        const val MAX_TYPES = 8
        const val DEFAULT_MAX_POINTS = 300
        const val MAX_POINTS = 1000
    }
}
