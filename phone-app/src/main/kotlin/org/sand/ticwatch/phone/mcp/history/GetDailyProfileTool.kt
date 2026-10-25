package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.sqrt

/** Cyclic grouping of rollup buckets; [argName] is how the tool accepts it. */
enum class ProfileGrouping(val argName: String, val resolution: RollupResolution) {
    HOUR_OF_DAY("hour_of_day", RollupResolution.HOUR) {
        override fun key(bucketStart: Instant, zone: ZoneId) = bucketStart.atZone(zone).hour
        override fun label(key: Int) = "%02d:00".format(key)
    },
    DAY_OF_WEEK("day_of_week", RollupResolution.DAY) {
        override fun key(bucketStart: Instant, zone: ZoneId) = bucketStart.atZone(zone).dayOfWeek.value
        override fun label(key: Int) = DayOfWeek.of(key).getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    };

    abstract fun key(bucketStart: Instant, zone: ZoneId): Int
    abstract fun label(key: Int): String
}

/** Typical value of a sensor type per hour of day or day of week: the baseline a forecast or anomaly check compares against. */
class GetDailyProfileTool(
    private val queries: SensorHistoryQueries,
    private val parser: HistoryToolArguments,
    private val catalog: SensorMetricCatalog,
    private val format: HistoryToolFormat,
    private val zone: ZoneId,
) : McpTool {
    override val name = "get_daily_profile"
    override val description =
        "Recurring pattern of one sensor type: groups its hourly (hour_of_day, ranges up to 365 days back) or daily " +
            "(day_of_week, any range) buckets and returns, per group, mean/sd/min/max of the per-bucket statistic " +
            "across the range plus how many buckets contributed. The statistic is the type's natural one (e.g. hourly " +
            "step sum, hourly average heart rate) unless 'stat' overrides it. Use it for circadian/weekly baselines, " +
            "anomaly detection and seasonal-naive forecasts. Default range: the last $DEFAULT_DAYS days."
    override val inputSchema: JSONObject = HistoryToolSchema.objectSchema(
        JSONObject()
            .put(HistoryToolSchema.TYPE, HistoryToolSchema.typeProperty())
            .put(
                GROUP_BY,
                JSONObject().put("type", "string")
                    .put("enum", JSONArray(ProfileGrouping.entries.map { it.argName }))
                    .put("default", ProfileGrouping.HOUR_OF_DAY.argName),
            )
            .put(HistoryToolSchema.FROM, HistoryToolSchema.fromProperty().put("description", "Range start, inclusive. Default: $DEFAULT_DAYS days before 'to'"))
            .put(HistoryToolSchema.TO, HistoryToolSchema.toProperty())
            .put(HistoryToolSchema.STAT, HistoryToolSchema.statProperty()),
        required = listOf(HistoryToolSchema.TYPE),
    )

    override suspend fun call(arguments: JSONObject): String {
        val type = parser.sensorType(arguments)
        val grouping = grouping(arguments)
        val range = parser.rangeWithDefaultSpan(arguments, DEFAULT_SPAN)
        require(grouping.resolution.covers(range.from, parser.now())) {
            "${grouping.argName} needs ${grouping.resolution.name.lowercase()} buckets, kept for " +
                "${grouping.resolution.retention?.toDays()} days only; move 'from' later"
        }
        val stat = parser.stat(arguments) ?: catalog.describe(type).kind.defaultStat

        val groups = queries.rollups(type, grouping.resolution, range.from, range.to)
            .groupBy({ grouping.key(it.bucketStart, zone) }, { stat.of(it) })
            .toSortedMap()
        val profile = JSONArray()
        groups.forEach { (key, values) ->
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            profile.put(
                JSONObject()
                    .put("group", grouping.label(key))
                    .put("mean", format.number(mean))
                    .put("sd", format.number(sqrt(variance)))
                    .put("min", format.number(values.min()))
                    .put("max", format.number(values.max()))
                    .put("buckets", values.size),
            )
        }
        return JSONObject()
            .put("type", type.name)
            .put("range", format.range(range))
            .put("group_by", grouping.argName)
            .put("stat", "${grouping.resolution.name.lowercase()} ${stat.argName}")
            .put("profile", profile)
            .toString()
    }

    private fun grouping(arguments: JSONObject): ProfileGrouping {
        val name = arguments.optString(GROUP_BY).takeIf { it.isNotBlank() } ?: return ProfileGrouping.HOUR_OF_DAY
        return ProfileGrouping.entries.firstOrNull { it.argName == name }
            ?: throw IllegalArgumentException("'$GROUP_BY' must be one of ${ProfileGrouping.entries.joinToString { it.argName }}")
    }

    private companion object {
        const val GROUP_BY = "group_by"
        const val DEFAULT_DAYS = 28L
        val DEFAULT_SPAN: Duration = Duration.ofDays(DEFAULT_DAYS)
    }
}
