package org.sand.ticwatch.phone.insights

import androidx.annotation.StringRes
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The temperatures the "Temperature" card plots together, each from its own history sensor. */
enum class TemperatureSeries(val sensor: SensorType, @StringRes val label: Int) {
    BODY(SensorType.BODY_TEMPERATURE, R.string.temperature_body),
    WRIST(SensorType.SKIN_TEMPERATURE, R.string.temperature_wrist),
    OUTDOOR(SensorType.AMBIENT_TEMPERATURE, R.string.temperature_outdoor),
}

/** How far back a temperature chart reaches, and at which rollup resolution, so long spans stay light to draw. */
enum class TemperatureSpan(val days: Long, val resolution: RollupResolution, @StringRes val label: Int) {
    DAY(1, RollupResolution.FIVE_MINUTES, R.string.temperature_span_day),
    THREE_DAYS(3, RollupResolution.FIVE_MINUTES, R.string.temperature_span_three_days),
    WEEK(7, RollupResolution.HOUR, R.string.temperature_span_week),
}

/** All [TemperatureSeries] over one [span] ending now, with the local midnights inside it for day markers. */
data class TemperatureWindow(
    val span: TemperatureSpan,
    val start: Instant,
    val end: Instant,
    val dayStarts: List<Instant>,
    val series: Map<TemperatureSeries, Trend>,
) {
    /** The newest value of [series] in this window, or null when it has none. */
    fun latest(series: TemperatureSeries): Double? = this.series[series]?.points?.lastOrNull()?.value
}

/** Read side of the temperature card and its detail sheet. */
fun interface TemperatureHistory {
    suspend fun window(span: TemperatureSpan): TemperatureWindow
}

/** Builds [TemperatureWindow]s from the history rollups, never scanning raw rows. */
class RollupTemperatureHistory(
    private val queries: SensorHistoryQueries,
    private val clock: Clock,
    private val zone: ZoneId,
) : TemperatureHistory {

    override suspend fun window(span: TemperatureSpan): TemperatureWindow {
        val end = clock.instant()
        val start = end.minus(Duration.ofDays(span.days))
        val series = TemperatureSeries.entries.associateWith { series ->
            val points = queries.rollups(series.sensor, span.resolution, start, end).map { TrendPoint(it.bucketStart, it.average) }
            Trend(points, start, end)
        }
        return TemperatureWindow(span, start, end, localMidnights(start, end), series)
    }

    private fun localMidnights(start: Instant, end: Instant): List<Instant> =
        generateSequence(start.atZone(zone).toLocalDate().plusDays(1)) { it.plusDays(1) }
            .map { it.atStartOfDay(zone).toInstant() }
            .takeWhile { it < end }
            .toList()
}
