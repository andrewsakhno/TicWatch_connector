package org.sand.ticwatch.phone.mcp.history

import org.json.JSONObject
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool

/** Bulk dump of retained raw samples as compact CSV pages, for offline analysis by the calling model. */
class ExportSamplesTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "export_samples"
    override val description =
        "Bulk export of raw samples of one, several or all sensor types (default all) as CSV " +
            "'type,start,end,value' ordered by start; 'end' is empty for point samples and set for intervals " +
            "(sleep/exercise sessions, step/calorie intervals, watch measurement windows). Covers everything still retained (3-30 days per " +
            "Settings; older data exists only as get_series/get_multi_series buckets). A full page carries " +
            "'next_cursor' - repeat with it as 'cursor' until it is absent to get the complete history."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.TYPES, HistoryToolSchema.typesProperty("Sensor types to export. Default: all"))
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(HistoryToolSchema.LIMIT, HistoryToolSchema.intProperty("Maximum rows per page", DEFAULT_LIMIT, MAX_LIMIT))
            .put(HistoryToolSchema.CURSOR, HistoryToolSchema.cursorProperty()),
    )

    override suspend fun call(arguments: JSONObject): String {
        val types = parser.sensorTypes(arguments)
        val range = parser.range(arguments)
        val limit = parser.boundedInt(arguments, HistoryToolSchema.LIMIT, DEFAULT_LIMIT, MAX_LIMIT)

        val rows = queries.rawSamples(types, range.from, range.to, parser.cursor(arguments), limit)
        val csv = buildString {
            append(CSV_HEADER)
            rows.forEach { row ->
                val sample = row.sample
                append('\n').append(sample.type.name)
                    .append(',').append(format.time(sample.recordedAt))
                    .append(',').append(sample.endedAt?.let(format::time).orEmpty())
                    .append(',').append(format.number(sample.value))
            }
        }
        val result = JSONObject()
            .put("range", format.range(range))
            .put("rows", rows.size)
            .put("csv", csv)
        if (rows.size == limit) result.put("next_cursor", format.cursor(rows.last().cursor))
        return result.toString()
    }

    private companion object {
        const val CSV_HEADER = "type,start,end,value"
        const val DEFAULT_LIMIT = 2000
        const val MAX_LIMIT = 5000
    }
}
