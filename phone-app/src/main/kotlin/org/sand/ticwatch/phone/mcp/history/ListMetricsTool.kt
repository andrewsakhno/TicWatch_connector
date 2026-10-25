package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.history.HistoryRetentionStore
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.history.SensorHistoryStorageSize
import org.sand.ticwatch.phone.history.SleepSessionRetentionStore
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Lists the sensor types with stored data, what their values mean, and how far back each data tier reaches. */
class ListMetricsTool(
    private val queries: SensorHistoryQueries,
    private val retentionStore: HistoryRetentionStore,
    private val sleepSessionRetentionStore: SleepSessionRetentionStore,
    private val storageSize: SensorHistoryStorageSize,
    private val catalog: SensorMetricCatalog,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
    private val zone: ZoneId,
) : McpTool {
    override val name = "list_metrics"
    override val description =
        "Call first. Lists sensor types recorded from the TicWatch (plus phone-derived metrics) with unit, kind " +
            "(how to aggregate), first/last day with data and sample count; plus the current time and zone, raw " +
            "sample retention, sleep-session retention (sleep stages share it; SLEEP_STAGE values are stage codes, decoded " +
            "in sleep_sessions.stage_codes) and how far back each bucket tier (5 minutes, hour, day) reaches. " +
            "STEPS_CADENCE (steps/min while walking/running) and BASAL_METABOLIC_RATE (kcal/day) are phone-derived."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(JSONObject())

    override suspend fun call(arguments: JSONObject): String {
        val now = parser.now()
        val rawRetentionDays = retentionStore.get().days

        val tiers = JSONArray()
        RollupResolution.entries.forEach { resolution ->
            val tier = JSONObject()
                .put("resolution", format.resolution(resolution))
                .put("bucket_minutes", resolution.bucket.toMinutes())
            resolution.retention?.let { retention ->
                tier.put("retention_days", retention.toDays()).put("available_from", format.time(now.minus(retention)))
            }
            tiers.put(tier)
        }

        val metrics = JSONArray()
        queries.coverage().forEach { coverage ->
            val descriptor = catalog.describe(coverage.type)
            metrics.put(
                JSONObject()
                    .put("type", coverage.type.name)
                    .put("unit", descriptor.unit)
                    .put("kind", descriptor.kind.name.lowercase())
                    .put("aggregate", descriptor.kind.guidance)
                    .put("note", descriptor.note)
                    .put("first_day", format.time(coverage.firstDay))
                    .put("last_day", format.time(coverage.lastDay))
                    .put("samples", coverage.sampleCount),
            )
        }
        return JSONObject()
            .put("now", format.time(now))
            .put("time_zone", zone.id)
            .put(
                "raw_samples",
                JSONObject()
                    .put("retention_days", rawRetentionDays)
                    .put("available_from", format.time(now.minus(Duration.ofDays(rawRetentionDays)))),
            )
            .put("tiers", tiers)
            .put("sleep_sessions", sleepSessionRetention(now).put("stage_codes", stageCodes()))
            .put("storage_bytes", storageSize.storageSizeBytes())
            .put("metrics", metrics)
            .toString()
    }

    /** SLEEP_STAGE value -> stage name; per-session stages come from get_sleep_sessions. */
    private fun stageCodes(): JSONObject =
        SleepStageType.entries.fold(JSONObject()) { codes, type -> codes.put(type.code.toString(), type.name.lowercase()) }

    private suspend fun sleepSessionRetention(now: Instant): JSONObject {
        val months = sleepSessionRetentionStore.get().months ?: return JSONObject().put("retention", "forever")
        return JSONObject()
            .put("retention_months", months)
            .put("available_from", format.time(now.atZone(zone).minusMonths(months).toInstant()))
    }
}
