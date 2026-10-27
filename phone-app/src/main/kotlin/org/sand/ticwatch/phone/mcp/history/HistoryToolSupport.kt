package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupPoint
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SampleCursor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Half-open time range [from, to). */
data class TimeRange(val from: Instant, val to: Instant) {
    val duration: Duration get() = Duration.between(from, to)
}

/** Argument names and JSON Schema fragments shared by the history tools. */
object HistoryToolSchema {
    const val TYPE = "type"
    const val FROM = "from"
    const val TO = "to"
    const val MAX_POINTS = "max_points"
    const val LIMIT = "limit"
    const val TYPES = "types"
    const val RESOLUTION = "resolution"
    const val CURSOR = "cursor"
    const val STAT = "stat"

    private const val TIME_FORMATS =
        "ISO-8601: instant (2026-10-25T08:00:00Z), with offset, local date-time or local date (phone's time zone)"

    fun typeProperty(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(SensorType.entries.map { it.name }))
        .put("description", "Sensor type")

    fun typesProperty(description: String): JSONObject = JSONObject()
        .put("type", "array")
        .put("items", typeProperty())
        .put("uniqueItems", true)
        .put("description", description)

    fun resolutionProperty(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(RollupResolution.entries.map { it.name.lowercase() }))
        .put("description", "Force a bucket size instead of the automatic choice; must still be retained for 'from'")

    fun cursorProperty(): JSONObject = JSONObject().put("type", "string")
        .put("description", "Opaque 'next_cursor' from the previous page; omit for the first page")

    fun statProperty(): JSONObject = JSONObject()
        .put("type", "string")
        .put("enum", JSONArray(SeriesStat.entries.map { it.argName }))
        .put("description", "Statistic per bucket for every type. Default: each type's natural one (see list_metrics)")

    fun fromProperty(): JSONObject = JSONObject().put("type", "string")
        .put("description", "Range start, inclusive. $TIME_FORMATS. Default: 24 hours before 'to'")

    fun toProperty(): JSONObject = JSONObject().put("type", "string")
        .put("description", "Range end, exclusive. $TIME_FORMATS. Default: now")

    /** `from`/`to` schema of a tool whose range defaults to the [defaultDays] days before `to`. */
    fun dayRangeSchema(defaultDays: Long): JSONObject = objectSchema(
        JSONObject()
            .put(FROM, fromProperty().put("description", "Range start, inclusive. $TIME_FORMATS. Default: $defaultDays days before 'to'"))
            .put(TO, toProperty()),
    )

    fun intProperty(description: String, default: Int, max: Int): JSONObject = JSONObject()
        .put("type", "integer").put("minimum", 1).put("maximum", max).put("default", default)
        .put("description", description)

    fun objectSchema(properties: JSONObject, required: List<String> = emptyList()): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required))
}

/** Parses and validates history-tool arguments; throws [IllegalArgumentException] with a model-readable message. */
class HistoryToolArguments(private val clock: Clock, private val zone: ZoneId) {

    fun now(): Instant = clock.instant()

    fun sensorType(arguments: JSONObject): SensorType {
        val name = arguments.optString(HistoryToolSchema.TYPE)
        return SensorType.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("'${HistoryToolSchema.TYPE}' must be one of ${SensorType.entries.joinToString()}")
    }

    fun range(arguments: JSONObject): TimeRange = rangeWithDefaultSpan(arguments, DEFAULT_SPAN)

    /** Like [range], but a missing 'from' defaults to [defaultSpan] before 'to'. */
    fun rangeWithDefaultSpan(arguments: JSONObject, defaultSpan: Duration): TimeRange {
        val to = optTime(arguments, HistoryToolSchema.TO) ?: clock.instant()
        val from = optTime(arguments, HistoryToolSchema.FROM) ?: to.minus(defaultSpan)
        require(from.isBefore(to)) { "'${HistoryToolSchema.FROM}' must be before '${HistoryToolSchema.TO}'" }
        return TimeRange(from, to)
    }

    /** Types listed under [HistoryToolSchema.TYPES], or every type when the argument is absent or empty. */
    fun sensorTypes(arguments: JSONObject): Set<SensorType> {
        val names = arguments.optJSONArray(HistoryToolSchema.TYPES) ?: return SensorType.entries.toSet()
        if (names.length() == 0) return SensorType.entries.toSet()
        return (0 until names.length()).mapTo(LinkedHashSet()) { index ->
            sensorType(JSONObject().put(HistoryToolSchema.TYPE, names.optString(index)))
        }
    }

    fun resolution(arguments: JSONObject): RollupResolution? {
        val name = arguments.optString(HistoryToolSchema.RESOLUTION).takeIf { it.isNotBlank() } ?: return null
        return RollupResolution.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "'${HistoryToolSchema.RESOLUTION}' must be one of ${RollupResolution.entries.joinToString { it.name.lowercase() }}",
            )
    }

    fun stat(arguments: JSONObject): SeriesStat? {
        val name = arguments.optString(HistoryToolSchema.STAT).takeIf { it.isNotBlank() } ?: return null
        return SeriesStat.entries.firstOrNull { it.argName == name }
            ?: throw IllegalArgumentException("'${HistoryToolSchema.STAT}' must be one of ${SeriesStat.entries.joinToString { it.argName }}")
    }

    fun cursor(arguments: JSONObject): SampleCursor? {
        val token = arguments.optString(HistoryToolSchema.CURSOR).takeIf { it.isNotBlank() } ?: return null
        return SampleCursorToken.decode(token)
            ?: throw IllegalArgumentException("'${HistoryToolSchema.CURSOR}' is not a cursor returned by this server: $token")
    }

    fun boundedInt(arguments: JSONObject, key: String, default: Int, max: Int): Int =
        if (arguments.has(key)) arguments.getInt(key).coerceIn(1, max) else default

    fun optTime(arguments: JSONObject, key: String): Instant? {
        val text = arguments.optString(key).takeIf { it.isNotBlank() } ?: return null
        return TIME_PARSERS.firstNotNullOfOrNull { parse -> runCatching { parse(text, zone) }.getOrNull() }
            ?: throw IllegalArgumentException("'$key' is not an ISO-8601 time: $text")
    }

    private companion object {
        val DEFAULT_SPAN: Duration = Duration.ofHours(24)

        val TIME_PARSERS: List<(String, ZoneId) -> Instant> = listOf(
            { text, _ -> Instant.parse(text) },
            { text, _ -> OffsetDateTime.parse(text).toInstant() },
            { text, zone -> LocalDateTime.parse(text).atZone(zone).toInstant() },
            { text, zone -> LocalDate.parse(text).atStartOfDay(zone).toInstant() },
        )
    }
}

/** Renders history data compactly: local-offset timestamps, values rounded to [DECIMALS] places. */
class HistoryToolFormat(private val zone: ZoneId) {

    fun time(instant: Instant): String = instant.atZone(zone).toOffsetDateTime().toString()

    fun number(value: Double): Double = Math.round(value * SCALE) / SCALE

    fun range(range: TimeRange): JSONObject = JSONObject().put("from", time(range.from)).put("to", time(range.to))

    fun aggregate(point: RollupPoint): JSONObject = JSONObject()
        .put("min", number(point.min))
        .put("max", number(point.max))
        .put("avg", number(point.average))
        .put("sum", number(point.sum))
        .put("count", point.count)

    fun resolution(resolution: RollupResolution): String = resolution.name.lowercase()

    fun cursor(cursor: SampleCursor): String = SampleCursorToken.encode(cursor)

    private companion object {
        const val DECIMALS = 2
        val SCALE = Math.pow(10.0, DECIMALS.toDouble())
    }
}

/** Opaque text form of a [SampleCursor]: `<recorded epoch ms>.<row id>`. */
internal object SampleCursorToken {
    private const val SEPARATOR = '.'

    fun encode(cursor: SampleCursor): String = "${cursor.recordedAt.toEpochMilli()}$SEPARATOR${cursor.id}"

    fun decode(token: String): SampleCursor? {
        val recordedAt = token.substringBefore(SEPARATOR, "").toLongOrNull() ?: return null
        val id = token.substringAfter(SEPARATOR, "").toLongOrNull() ?: return null
        return SampleCursor(Instant.ofEpochMilli(recordedAt), id)
    }
}
