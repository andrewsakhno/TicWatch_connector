package org.sand.ticwatch.phone.mcp.watch

import org.json.JSONObject
import org.sand.ticwatch.phone.datalayer.WatchWorkoutCommands
import org.sand.ticwatch.phone.datalayer.WatchWorkoutOutcome
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.mcp.history.HistoryToolSchema

private const val STATUS = "status"

private fun outcomeJson(result: Result<WatchWorkoutOutcome>): String {
    val outcome = result.getOrElse { throw IllegalStateException("No answer from the watch: ${it.message}", it) }
    return JSONObject().put(STATUS, outcome.name).toString()
}

/** Starts the watch's walk workout. */
class StartWorkoutTool(private val commands: WatchWorkoutCommands) : McpTool {
    override val name = "start_workout"
    override val description =
        "Starts a WALKING workout on the watch now, recorded as an automatic walk (AUTO_EXERCISE), whatever the watch's " +
            "automatic-workout setting. It ends by itself after 5 min below 60 steps/min, so it doesn't suit workouts " +
            "without steps; a session under 3 min isn't recorded. Returns `status`: STARTED, ALREADY_RUNNING (this app's), " +
            "OTHER_APP_RUNNING (left alone, nothing started) or FAILED. An error means no answer came, so the command may " +
            "still have run."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(JSONObject())

    override suspend fun call(arguments: JSONObject): String = outcomeJson(commands.startWatchWorkout())
}

/** Ends the workout this app runs on the watch. */
class EndWorkoutTool(private val commands: WatchWorkoutCommands) : McpTool {
    override val name = "end_workout"
    override val description =
        "Ends the workout this app runs on the watch; its session then reaches the history with the watch's next sync. " +
            "Returns `status`: ENDED, NOT_RUNNING, OTHER_APP_RUNNING (left alone) or FAILED. An error means no answer came, " +
            "so the command may still have run."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(JSONObject())

    override suspend fun call(arguments: JSONObject): String = outcomeJson(commands.endWatchWorkout())
}
