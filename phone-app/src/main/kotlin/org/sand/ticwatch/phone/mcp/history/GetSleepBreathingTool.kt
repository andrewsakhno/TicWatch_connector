package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SleepSession
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration
import java.time.Instant

/** Per-night breathing from the watch's night monitor: oxygen desaturations, SpO2, snoring, breathing rate, HRV. */
class GetSleepBreathingTool(
    private val queries: SensorHistoryQueries,
    private val reader: SampleWindowReader,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_sleep_breathing"
    override val description =
        "Per sleep session: oxygen desaturation index (ODI, >= 3-point SpO2 drops per monitored hour; a wrist " +
            "screening value, not a diagnosis: <5 normal, 5-15 mild, 15-30 moderate, >30 severe), desaturation " +
            "count and deepest drop, SpO2 (5-min epoch medians: min/mean), snoring minutes (estimated from 1 min of " +
            "audio features per 5-min epoch), mean breathing rate and RMSSD while asleep. Null where the night " +
            "monitor had no data (watch off-wrist, microphone not granted). Reads raw rows, so it reaches back as " +
            "far as the raw retention. Default range: the last $DEFAULT_DAYS days, oldest first."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(
                HistoryToolSchema.FROM,
                HistoryToolSchema.fromProperty().put("description", "Range start, inclusive. Default: $DEFAULT_DAYS days before 'to'"),
            )
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty()),
    )

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, DEFAULT_SPAN)
        val nights = JSONArray()
        sessions(range.from, range.to).forEach { session ->
            nights.put(night(session, reader.samples(NIGHT_TYPES, session.start, session.end, emptyList())))
        }
        return JSONObject().put("range", format.range(range)).put("nights", nights).toString()
    }

    private fun night(session: SleepSession, samples: List<SensorSample>): JSONObject {
        fun of(type: SensorType) = samples.filter { it.type == type }
        val epochSpo2 = of(SensorType.SPO2).filter { it.endedAt != null }.map { it.value }
        val desaturations = of(SensorType.OXYGEN_DESATURATION)
        return JSONObject()
            .put("start", format.time(session.start))
            .put("end", format.time(session.end))
            .put("odi_per_hour", nullable(of(SensorType.OXYGEN_DESATURATION_INDEX).lastOrNull()?.value))
            .put("desaturations", desaturations.size)
            .put("deepest_drop_points", nullable(desaturations.maxOfOrNull { it.value }))
            .put("spo2_monitored_hours", format.number(epochHours(of(SensorType.SPO2))))
            .put("spo2_min", nullable(epochSpo2.minOrNull()))
            .put("spo2_mean", nullable(epochSpo2.takeIf { it.isNotEmpty() }?.average()))
            .put("snoring_minutes", nullable(of(SensorType.SNORING_DURATION).lastOrNull()?.value))
            .put("snore_epochs_measured", of(SensorType.SNORING).size)
            .put("breathing_rate_mean", nullable(of(SensorType.RESPIRATION_RATE).takeIf { it.isNotEmpty() }?.map { it.value }?.average()))
            .put("rmssd_mean_ms", nullable(of(SensorType.HRV_RMSSD).takeIf { it.isNotEmpty() }?.map { it.value }?.average()))
    }

    private fun epochHours(epochs: List<SensorSample>): Double =
        epochs.mapNotNull { epoch -> epoch.endedAt?.let { Duration.between(epoch.recordedAt, it).toMillis() } }.sum() / MILLIS_PER_HOUR

    private fun nullable(value: Double?): Any = value?.let(format::number) ?: JSONObject.NULL

    private suspend fun sessions(from: Instant, to: Instant): List<SleepSession> = buildList {
        var afterStart: Instant? = null
        while (true) {
            val page = queries.sleepSessions(from, to, afterStart, PAGE_SIZE)
            addAll(page)
            if (page.size < PAGE_SIZE) break
            afterStart = page.last().start
        }
    }

    private companion object {
        const val DEFAULT_DAYS = 14L
        const val PAGE_SIZE = 200
        const val MILLIS_PER_HOUR = 3_600_000.0
        val DEFAULT_SPAN: Duration = Duration.ofDays(DEFAULT_DAYS)
        val NIGHT_TYPES = setOf(
            SensorType.SPO2, SensorType.OXYGEN_DESATURATION, SensorType.OXYGEN_DESATURATION_INDEX, SensorType.SNORING,
            SensorType.SNORING_DURATION, SensorType.RESPIRATION_RATE, SensorType.HRV_RMSSD,
        )
    }
}
