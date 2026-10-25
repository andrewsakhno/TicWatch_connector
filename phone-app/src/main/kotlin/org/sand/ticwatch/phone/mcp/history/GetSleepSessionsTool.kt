package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.history.SleepStageQueries
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration

/**
 * Sleep sessions from the long-lived `sleep_session` table, which outlives the raw-sample retention,
 * each with its stages from `sleep_stage` (same retention).
 */
class GetSleepSessionsTool(
    private val queries: SensorHistoryQueries,
    private val stageQueries: SleepStageQueries,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_sleep_sessions"
    override val description =
        "Sleep sessions (start, end, duration in minutes) overlapping a time range, oldest first, each with " +
            "minutes per sleep stage (${stageNames()}) and - unless '$INCLUDE_STAGES' is false - its stage intervals. " +
            "Kept for the sleep retention chosen in Settings (months, or forever - see list_metrics), far longer " +
            "than raw samples, so use this for sleep duration/timing/architecture trends and forecasts. A session " +
            "without stages has none recorded. Default range: the last $DEFAULT_DAYS days. A full page carries " +
            "'next_after' - pass it back as 'after' to read the rest."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(
                HistoryToolSchema.FROM,
                HistoryToolSchema.fromProperty().put("description", "Range start, inclusive. Default: $DEFAULT_DAYS days before 'to'"),
            )
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(HistoryToolSchema.LIMIT, HistoryToolSchema.intProperty("Maximum sessions returned", DEFAULT_LIMIT, MAX_LIMIT))
            .put(AFTER, JSONObject().put("type", "string").put("description", "'next_after' from the previous page"))
            .put(
                INCLUDE_STAGES,
                JSONObject().put("type", "boolean").put("default", true)
                    .put("description", "Include each session's stage intervals (per-stage minutes are always included)"),
            ),
    )

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, DEFAULT_SPAN)
        val limit = parser.boundedInt(arguments, HistoryToolSchema.LIMIT, DEFAULT_LIMIT, MAX_LIMIT)
        val after = parser.optTime(arguments, AFTER)
        val includeStages = arguments.optBoolean(INCLUDE_STAGES, true)

        val sessions = queries.sleepSessions(range.from, range.to, after, limit)
        val stagesBySession = stagesBySession(sessions)
        val rows = JSONArray()
        sessions.forEach { session ->
            val stages = stagesBySession[session].orEmpty()
            val row = JSONObject()
                .put("start", format.time(session.start))
                .put("end", format.time(session.end))
                .put("minutes", Duration.between(session.start, session.end).toMinutes())
                .put("stage_minutes", stageMinutes(stages))
            if (includeStages) row.put("stages", stageRows(stages))
            rows.put(row)
        }
        val result = JSONObject().put("range", format.range(range)).put("sessions", rows)
        if (sessions.size == limit) result.put("next_after", format.time(sessions.last().start))
        return result.toString()
    }

    /** One stage query for the whole page, then each stage goes to the session containing its start. */
    private suspend fun stagesBySession(sessions: List<SleepSession>): Map<SleepSession, List<SleepStage>> {
        if (sessions.isEmpty()) return emptyMap()
        val stages = stageQueries.sleepStages(sessions.first().start, sessions.maxOf { it.end })
        return sessions.associateWith { session ->
            stages.filter { !it.start.isBefore(session.start) && it.start.isBefore(session.end) }
        }
    }

    private fun stageMinutes(stages: List<SleepStage>): JSONObject =
        SleepStageType.entries.fold(JSONObject()) { minutes, type ->
            minutes.put(type.name.lowercase(), stages.filter { it.type == type }.sumOf { it.duration().toMinutes() })
        }

    private fun stageRows(stages: List<SleepStage>): JSONArray = stages.fold(JSONArray()) { rows, stage ->
        rows.put(
            JSONObject()
                .put("stage", stage.type.name.lowercase())
                .put("start", format.time(stage.start))
                .put("end", format.time(stage.end))
                .put("minutes", stage.duration().toMinutes()),
        )
    }

    private fun SleepStage.duration(): Duration = Duration.between(start, end)

    private companion object {
        const val AFTER = "after"
        const val INCLUDE_STAGES = "include_stages"
        const val DEFAULT_DAYS = 30L
        val DEFAULT_SPAN: Duration = Duration.ofDays(DEFAULT_DAYS)
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1000

        fun stageNames(): String = SleepStageType.entries.joinToString("/") { it.name.lowercase() }
    }
}
