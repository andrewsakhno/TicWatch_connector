package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.advisor.CardioReadinessSource
import org.sand.ticwatch.phone.advisor.ReadinessFactor
import org.sand.ticwatch.phone.mcp.McpTool

/** Current cardio readiness (the Advisor tab) for the agent to judge the user's state at a glance. */
class GetCardioReadinessTool(
    private val source: CardioReadinessSource,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_cardio_readiness"
    override val description =
        "Current cardio readiness for a user with hypertension and tachycardia: readiness_score 0..100 = 100 minus " +
            "penalties (diastolic >= 90 mmHg -25, >= 95 -40; heart rate > 10 bpm above the personal norm of this " +
            "hour -15, > 20 -30; RMSSD < 25 ms -15; > 15k steps in 24 h after a sleep score < 70 -15; Kp >= 5 -10) " +
            "plus +10 for a sleep score >= 70 with a 10-20 % nocturnal systolic dip. state: Optimal (>= 80), " +
            "Moderate load (>= 55), Limit cardio load (>= 35), Rest and monitor. limiting_factors are the penalised " +
            "inputs (strongest first), factors every evaluated input. forecast: the next hours re-scored with the " +
            "expected medication effect, morning surge, hourly pulse norm and Kp forecast; walk_window marks " +
            "favourable daytime hours. Heuristic, not a diagnosis."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(JSONObject())

    override suspend fun call(arguments: JSONObject): String {
        val readiness = source.load()
        val assessment = readiness.assessment
        val vitals = readiness.vitals
        val advice = assessment.advice
        val forecast = JSONArray()
        readiness.forecast.forEach { hour ->
            forecast.put(
                JSONObject()
                    .put("start", format.time(hour.start))
                    .put("score", hour.score)
                    .put("state", hour.state.label)
                    .put("walk_window", hour.walkWindow),
            )
        }
        val pressure = vitals.pressure?.let {
            JSONObject()
                .put("systolic", format.number(it.systolic))
                .put("diastolic", format.number(it.diastolic))
                .put("measured_at", format.time(it.measuredAt))
                .put("source", it.source.name.lowercase())
        }
        return JSONObject()
            .put("readiness_score", assessment.score)
            .put("state", assessment.state.label)
            .put("key_recommendation", JSONObject().put("action", advice.action.title).put("text", advice.text).put("rationale", advice.rationale ?: JSONObject.NULL))
            .put("limiting_factors", factors(assessment.limitingFactors))
            .put("factors", factors(assessment.factors))
            .put(
                "vitals",
                JSONObject()
                    .put("blood_pressure", pressure ?: JSONObject.NULL)
                    .put("heart_rate_bpm", vitals.heartRateBpm?.let(format::number) ?: JSONObject.NULL)
                    .put("body_temperature_c", vitals.bodyTemperatureCelsius?.let(format::number) ?: JSONObject.NULL)
                    .put("spo2_percent", vitals.spo2Percent?.let(format::number) ?: JSONObject.NULL),
            )
            .put("forecast", forecast)
            .put("timestamp", format.time(assessment.at))
            .toString()
    }

    private fun factors(factors: List<ReadinessFactor>): JSONArray = JSONArray().also { array ->
        factors.forEach { array.put(JSONObject().put("factor", it.kind.label).put("detail", it.detail).put("points", it.points)) }
    }
}
