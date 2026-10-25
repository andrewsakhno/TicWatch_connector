package org.sand.ticwatch.phone.mcp.history

import org.json.JSONObject
import org.sand.ticwatch.phone.history.DeliveryStatus
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool

/** Raw-sample counts per Health Connect delivery status, for diagnosing the watch -> Google Health sync. */
class GetDeliveryStatusTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_delivery_status"
    override val description =
        "Counts of samples the phone received in a time range by Google Health (Health Connect) delivery status: " +
            "PENDING, DELIVERED, FAILED. FAILED samples are re-sent by the daily maintenance job."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty()),
    )

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.range(arguments)
        val counts = queries.deliveryCounts(range.from, range.to)
        val byStatus = JSONObject()
        DeliveryStatus.entries.forEach { status -> byStatus.put(status.name, counts[status] ?: 0L) }
        return JSONObject().put("range", format.range(range)).put("counts", byStatus).toString()
    }
}
