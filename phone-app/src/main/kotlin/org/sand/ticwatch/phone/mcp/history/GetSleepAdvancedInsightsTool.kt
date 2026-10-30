package org.sand.ticwatch.phone.mcp.history

import org.json.JSONObject
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.sleep.SleepAdvancedInsights
import org.sand.ticwatch.phone.sleep.SleepAdvancedInsightsReader
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One night's advanced somnology: WASO, fragmentation, vagal recovery, snore-cardiac arousal, vasodilation, apnea risk. */
class GetSleepAdvancedInsightsTool(
    private val insights: SleepAdvancedInsightsReader,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_sleep_advanced_insights"
    override val description =
        "Advanced analysis of one night (the longest sleep session ending on 'date', the wake-up day). " +
            "waso_minutes = awake time between sleep onset and final awakening; awakenings_count = awake stages " +
            "interrupting sleep; fragmentation_index = 1 - longest uninterrupted sleep bout / total sleep (0 = one " +
            "unbroken block); vrr_ratio = mean night RMSSD / mean RMSSD of the preceding day 08-21 local (>= 1.5 " +
            "excellent, 1.1-1.5 moderate, 1.0-1.1 balanced, < 1.0 hidden autonomic stress); snore_cardiac_spikes_count " +
            "= snoring 5-min epochs whose mean heart rate exceeded the night's quiet-epoch median by > 10% (autonomic " +
            "stress snoring; none = benign acoustic snoring); vasodilation_time = first wrist skin temperature rise " +
            ">= 1.2 °C in the 2 h before sleep onset (opening of the sleep window); apnea_risk_level from the ODI " +
            "(< 5 low, 5-15 mild, 15-30 moderate, > 30 severe; raised to mild by >= 3 snore-cardiac spikes) - a wrist " +
            "screening value, not a diagnosis. Fields are null where data is missing (no stages, watch off-wrist). " +
            "Reads raw rows, so it reaches back as far as the raw retention."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject().put(
            DATE,
            JSONObject().put("type", "string").put("description", "Wake-up day, YYYY-MM-DD local. Default: the latest night"),
        ),
    )

    override suspend fun call(arguments: JSONObject): String {
        val date = arguments.optString(DATE).takeIf { it.isNotBlank() }?.let(::parseDate)
        val night = if (date != null) insights.nightEndingOn(date) else insights.latestNight()
        return (night?.let(::json) ?: JSONObject().put("night", JSONObject.NULL).put("reason", noNightReason(date))).toString()
    }

    private fun json(night: SleepAdvancedInsights): JSONObject {
        val continuity = night.continuity
        val recovery = night.recovery
        val snore = night.snore
        return JSONObject()
            .put("session_start", format.time(night.session.start))
            .put("session_end", format.time(night.session.end))
            .put("sleep_onset", continuity?.let { format.time(it.onset) } ?: JSONObject.NULL)
            .put("total_sleep_minutes", continuity?.totalSleep?.toMinutes() ?: JSONObject.NULL)
            .put("waso_minutes", continuity?.waso?.toMinutes() ?: JSONObject.NULL)
            .put("awakenings_count", continuity?.awakenings ?: JSONObject.NULL)
            .put("longest_sleep_bout_minutes", continuity?.longestSleepBout?.toMinutes() ?: JSONObject.NULL)
            .put("fragmentation_index", continuity?.let { format.number(it.fragmentationIndex) } ?: JSONObject.NULL)
            .put("vrr_ratio", recovery?.let { format.number(it.ratio) } ?: JSONObject.NULL)
            .put("vrr_status", recovery?.status?.let { format.text(it.label) } ?: JSONObject.NULL)
            .put("night_rmssd_ms", recovery?.let { format.number(it.nightRmssdMs) } ?: JSONObject.NULL)
            .put("day_rmssd_ms", recovery?.let { format.number(it.dayRmssdMs) } ?: JSONObject.NULL)
            .put("snore_episodes_total", snore.episodes)
            .put("snore_episodes_with_heart_rate", snore.assessedEpisodes)
            .put("snore_cardiac_spikes_count", snore.cardiacSpikes)
            .put("snore_cardiac_impact", format.text(snore.impact.label))
            .put("night_baseline_heart_rate_bpm", snore.baselineHeartRateBpm?.let(format::number) ?: JSONObject.NULL)
            .put("snoring_rmssd_ms", snore.snoringRmssdMs?.let(format::number) ?: JSONObject.NULL)
            .put("quiet_rmssd_ms", snore.quietRmssdMs?.let(format::number) ?: JSONObject.NULL)
            .put("vasodilation_time", night.vasodilationAt?.let(format::time) ?: JSONObject.NULL)
            .put("odi_per_hour", night.odiPerHour?.let(format::number) ?: JSONObject.NULL)
            .put("apnea_risk_level", format.text(night.apneaRisk.label))
    }

    private fun noNightReason(date: LocalDate?): String =
        date?.let { "No sleep session ends on $it" } ?: "No sleep session in the last few days"

    private fun parseDate(text: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("'$DATE' must be YYYY-MM-DD: $text", e)
    }

    private companion object {
        const val DATE = "date"
    }
}
