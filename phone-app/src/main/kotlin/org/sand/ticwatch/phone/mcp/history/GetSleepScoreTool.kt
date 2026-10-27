package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.insights.SleepQuality
import org.sand.ticwatch.phone.insights.SleepScore
import org.sand.ticwatch.phone.insights.SleepScoreComponent
import org.sand.ticwatch.phone.insights.SleepScoreSource
import org.sand.ticwatch.phone.insights.allSleepSessions
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration

/** Per-night sleep score with the breakdown into its weighted components. */
class GetSleepScoreTool(
    private val queries: SensorHistoryQueries,
    private val scores: SleepScoreSource,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_sleep_score"
    override val description =
        "Per sleep session: score 0-100 (>= 90 excellent, 80 good, 60 fair, below poor) and its components, each " +
            "0..1 with its weight (renormalised over the components that have data): duration (7.5-8.5 h asleep " +
            "ideal), stage_architecture (deep 15-25% and REM 20-25% of asleep time), efficiency (asleep / in bed, " +
            ">= 90% ideal), heart_rate_relaxation (nocturnal HR dip below the 12 h before bed, >= 10% ideal) and " +
            "breathing (ODI, <= 5/h ideal). Also the raw inputs. Heuristic, not clinically validated. Stages and " +
            "ODI come from raw rows, so it reaches back as far as the raw retention. Default range: the last " +
            "$DEFAULT_DAYS days, oldest first."
    override val inputSchema: JSONObject = HistoryToolSchema.dayRangeSchema(DEFAULT_DAYS)

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, Duration.ofDays(DEFAULT_DAYS))
        val nights = JSONArray()
        queries.allSleepSessions(range.from, range.to).forEach { session ->
            nights.put(night(scores.score(session, emptyList())).put("start", format.time(session.start)).put("end", format.time(session.end)))
        }
        return JSONObject().put("range", format.range(range)).put("nights", nights).toString()
    }

    private fun night(score: SleepScore): JSONObject {
        val components = JSONObject()
        SleepScoreComponent.entries.forEach { component ->
            val value = score.components[component]
            components.put(
                component.key,
                if (value == null) JSONObject.NULL else JSONObject().put("value", format.number(value)).put("weight", component.weight),
            )
        }
        val inputs = score.inputs
        val stages = JSONObject()
        inputs.stageDurations.forEach { (stage, time) -> stages.put(stage.name.lowercase(), format.number(time.toMinutes().toDouble())) }
        return JSONObject()
            .put("score", score.score)
            .put("quality", SleepQuality.of(score.score).label)
            .put("components", components)
            .put(
                "inputs",
                JSONObject()
                    .put("in_bed_minutes", inputs.inBed.toMinutes())
                    .put("asleep_minutes", inputs.asleep.toMinutes())
                    .put("stage_minutes", stages)
                    .put("deep_share", nullable(inputs.asleepShare(SleepStageType.DEEP)))
                    .put("rem_share", nullable(inputs.asleepShare(SleepStageType.REM)))
                    .put("efficiency", nullable(inputs.efficiency))
                    .put("awakenings", inputs.awakenings)
                    .put("sleep_hr_bpm", nullable(inputs.sleepHeartRateBpm))
                    .put("daytime_hr_bpm", nullable(inputs.daytimeHeartRateBpm))
                    .put("hr_dip", nullable(inputs.heartRateDip))
                    .put("odi_per_hour", nullable(inputs.desaturationIndexPerHour)),
            )
    }

    private fun nullable(value: Double?): Any = value?.let(format::number) ?: JSONObject.NULL

    private companion object {
        const val DEFAULT_DAYS = 14L
    }
}
