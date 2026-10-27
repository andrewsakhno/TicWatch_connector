package org.sand.ticwatch.phone.mcp.watch

import org.json.JSONObject
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.WatchReplyAwaiter
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.mcp.history.HistoryToolArguments
import org.sand.ticwatch.phone.mcp.history.HistoryToolSchema
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Pulls the watch app's own logcat (kept 3 days on the watch) over the phone<->watch link. */
class GetWatchLogsTool(
    private val transport: DeviceLinkTransport,
    private val replies: WatchReplyAwaiter,
    private val parser: HistoryToolArguments,
) : McpTool {
    override val name = "get_watch_logs"
    override val description =
        "Watch app logcat lines (all tags of org.sand.ticwatch.watch, plus uncaught crashes), kept on the watch " +
            "for 3 days in hourly files, fetched over Bluetooth / Wear Data Layer - the watch needs no adb. " +
            "Returns the newest max_lines matching lines in [from, to) (default: the last hour); when " +
            "truncated, page backwards by passing the first returned line's time as `to`. Lines are " +
            "'yyyy-MM-dd HH:mm:ss.SSS pid tid level tag: message' in the watch's local time."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty())
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(CONTAINS, JSONObject().put("type", "string").put("description", "Case-insensitive substring filter, e.g. a tag."))
            .put(MAX_LINES, HistoryToolSchema.intProperty("Most lines to return.", DEFAULT_MAX_LINES, MAX_LINES_LIMIT)),
    )

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, DEFAULT_SPAN)
        val requestId = UUID.randomUUID().toString()
        val request = JSONObject()
            .put(REQUEST_ID, requestId)
            .put(FROM_MS, range.from.toEpochMilli())
            .put(TO_MS, range.to.toEpochMilli())
            .put(MAX_LINES, parser.boundedInt(arguments, MAX_LINES, DEFAULT_MAX_LINES, MAX_LINES_LIMIT))
            .apply { arguments.optString(CONTAINS).takeIf { it.isNotEmpty() }?.let { put(CONTAINS, it) } }
        val reply = replies.await(requestId, REPLY_TIMEOUT) {
            transport.sendMessage(DataLayerPaths.MESSAGE_DEBUG_LOGS_REQUEST, request.toString().toByteArray(Charsets.UTF_8))
        }.getOrElse { throw IllegalStateException("Could not reach the watch: ${it.message}", it) }
        return reply ?: throw IllegalStateException(
            "Request delivered, but no reply from the watch within $REPLY_TIMEOUT",
        )
    }

    private companion object {
        const val REQUEST_ID = "request_id"
        const val FROM_MS = "from_ms"
        const val TO_MS = "to_ms"
        const val CONTAINS = "contains"
        const val MAX_LINES = "max_lines"
        const val DEFAULT_MAX_LINES = 500
        const val MAX_LINES_LIMIT = 5_000
        val DEFAULT_SPAN: Duration = Duration.ofHours(1)
        val REPLY_TIMEOUT = 90.seconds
    }
}
