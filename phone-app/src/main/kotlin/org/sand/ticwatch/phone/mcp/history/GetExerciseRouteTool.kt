package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.route.ExerciseRouteQueries
import java.time.Duration

/** Workout GPS fixes (phone- and watch-recorded) in a time range, for mapping a session's route. */
class GetExerciseRouteTool(
    private val routes: ExerciseRouteQueries,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_exercise_route"
    override val description =
        "GPS route points (time, lat, lon, altitude_m, accuracy_m, source=phone|watch) recorded during workouts, " +
            "oldest first. Pass an EXERCISE session's start/end (from get_raw_samples with types=[\"EXERCISE\"]) " +
            "as from/to to get that session's route. Kept for the raw-sample retention. Default range: the last " +
            "$DEFAULT_DAYS days. A full page carries 'next_after' - pass it back as 'after' to read the rest."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(
                HistoryToolSchema.FROM,
                HistoryToolSchema.fromProperty().put("description", "Range start, inclusive. Default: $DEFAULT_DAYS days before 'to'"),
            )
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(HistoryToolSchema.LIMIT, HistoryToolSchema.intProperty("Maximum points returned", DEFAULT_LIMIT, MAX_LIMIT))
            .put(AFTER, JSONObject().put("type", "string").put("description", "'next_after' from the previous page")),
    )

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, DEFAULT_SPAN)
        val limit = parser.boundedInt(arguments, HistoryToolSchema.LIMIT, DEFAULT_LIMIT, MAX_LIMIT)
        val after = parser.optTime(arguments, AFTER)

        val points = routes.routePoints(range.from, range.to, after, limit)
        val rows = JSONArray()
        points.forEach { point ->
            val row = JSONObject()
                .put("time", format.time(point.recordedAt))
                .put("lat", point.latitude)
                .put("lon", point.longitude)
                .put("source", point.source.name.lowercase())
            point.altitudeMeters?.let { row.put("altitude_m", format.number(it)) }
            point.horizontalAccuracyMeters?.let { row.put("accuracy_m", format.number(it)) }
            rows.put(row)
        }
        val result = JSONObject().put("range", format.range(range)).put("points", rows)
        if (points.size == limit) result.put("next_after", format.time(points.last().recordedAt))
        return result.toString()
    }

    private companion object {
        const val AFTER = "after"
        const val DEFAULT_DAYS = 7L
        val DEFAULT_SPAN: Duration = Duration.ofDays(DEFAULT_DAYS)
        const val DEFAULT_LIMIT = 2000
        const val MAX_LIMIT = 10_000
    }
}
