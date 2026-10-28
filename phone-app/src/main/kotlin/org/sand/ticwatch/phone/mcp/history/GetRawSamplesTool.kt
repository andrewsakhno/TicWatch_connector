package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool

/** Individual raw samples of a sensor type, with their Health Connect delivery state. */
class GetRawSamplesTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_raw_samples"
    override val description =
        "Individual samples of one sensor type exactly as received from the watch, oldest first, with when the " +
            "phone received each and its Google Health (Health Connect) delivery status. Raw samples are kept only " +
            "for the retention chosen in Settings (3-30 days); prefer get_series/get_summary for trends. A full page " +
            "carries 'next_cursor' - pass it back as 'cursor' to read the rest."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.TYPE, HistoryToolSchema.typeProperty())
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(HistoryToolSchema.LIMIT, HistoryToolSchema.intProperty("Maximum samples returned", DEFAULT_LIMIT, MAX_LIMIT))
            .put(HistoryToolSchema.CURSOR, HistoryToolSchema.cursorProperty()),
        required = listOf(HistoryToolSchema.TYPE),
    )

    override suspend fun call(arguments: JSONObject): String {
        val type = parser.sensorType(arguments)
        val range = parser.range(arguments)
        val limit = parser.boundedInt(arguments, HistoryToolSchema.LIMIT, DEFAULT_LIMIT, MAX_LIMIT)

        val rows = queries.rawSamples(setOf(type), range.from, range.to, parser.cursor(arguments), limit)
        val samples = JSONArray()
        rows.forEach { row ->
            val sample = JSONObject()
                .put("t", format.time(row.sample.recordedAt))
                .put("value", format.number(row.sample.value))
                .put("received", format.time(row.receivedAt))
                .put("status", row.status.name)
            row.sample.endedAt?.let { sample.put("end", format.time(it)) }
            if (row.sample.snapshot) sample.put("snapshot", true)
            row.statusUpdatedAt?.let { sample.put("status_at", format.time(it)) }
            samples.put(sample)
        }
        val result = JSONObject()
            .put("type", type.name)
            .put("range", format.range(range))
            .put("samples", samples)
        if (rows.size == limit) result.put("next_cursor", format.cursor(rows.last().cursor))
        return result.toString()
    }

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1000
    }
}
