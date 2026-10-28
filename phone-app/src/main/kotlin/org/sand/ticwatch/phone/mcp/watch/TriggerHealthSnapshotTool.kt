package org.sand.ticwatch.phone.mcp.watch

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.mcp.history.HistoryToolFormat
import org.sand.ticwatch.phone.mcp.history.HistoryToolSchema
import org.sand.ticwatch.phone.snapshot.WatchSnapshotRequester
import kotlin.time.Duration.Companion.minutes

/** Asks the watch for a Baseline Snapshot (all metrics at once, at rest) and returns its readings. */
class TriggerHealthSnapshotTool(
    private val requester: WatchSnapshotRequester,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "trigger_health_snapshot"
    override val description =
        "Takes a Baseline Snapshot on the watch now: checks the watch is worn and the wrist still (10 s accelerometer + " +
            "gyroscope), then measures heart rate, SpO2, HRV (RMSSD), breathing rate, skin and ambient temperature, " +
            "barometric pressure and light together in one ~3 min window. The readings are stored in the history with " +
            "is_snapshot = 1 (get_raw_samples marks them `snapshot: true`) and a wrist blood pressure estimate is saved " +
            "from the snapshot's heart rate (see get_blood_pressure). With wait=true (default) blocks until the result " +
            "arrives (up to 5 min); status is COMPLETED, NOT_WORN, NOT_STILL, NO_READINGS or BUSY."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject().put(
            WAIT,
            JSONObject().put("type", "boolean").put("default", true)
                .put("description", "Wait for the snapshot result instead of returning once the command was sent."),
        ),
    )

    override suspend fun call(arguments: JSONObject): String {
        if (!arguments.optBoolean(WAIT, true)) {
            val requestId = requester.request()
                .getOrElse { throw IllegalStateException("Could not reach the watch: ${it.message}", it) }
            return JSONObject().put("requested", true).put("request_id", requestId).toString()
        }
        val result = requester.requestAndAwait(REPLY_TIMEOUT)
            .getOrElse { throw IllegalStateException("Could not reach the watch: ${it.message}", it) }
            ?: throw IllegalStateException("Request delivered, but no snapshot result within $REPLY_TIMEOUT")
        val samples = JSONArray()
        result.samples.forEach { sample ->
            samples.put(
                JSONObject()
                    .put("type", sample.type.name)
                    .put("value", format.number(sample.value))
                    .put("t", format.time(sample.recordedAt)),
            )
        }
        return JSONObject()
            .put("status", result.status.name)
            .put("message", result.message)
            .put("started", format.time(result.startedAt))
            .put("finished", format.time(result.finishedAt))
            .put("samples", samples)
            .toString()
    }

    private companion object {
        const val WAIT = "wait"
        val REPLY_TIMEOUT = 5.minutes
    }
}
