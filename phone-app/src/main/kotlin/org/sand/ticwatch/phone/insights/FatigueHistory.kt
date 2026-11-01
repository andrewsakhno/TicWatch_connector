package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupPoint
import org.sand.ticwatch.phone.history.RollupResolution
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val FATIGUE_CHART_COUNT = 7
private val LAST_MINUTE_OF_DAY = LocalTime.of(23, 59)

/** The fixed value axis of the fatigue charts, so that days can be compared at a glance. */
val FATIGUE_INDEX_SCALE = 0.0..100.0

/** The spans the fatigue history can show, today included: always seven charts, each overlaying [daysPerChart] consecutive days. */
enum class FatigueHistorySpan(val daysPerChart: Int) {
    ONE_WEEK(1),
    TWO_WEEKS(2),
    THREE_WEEKS(3),
    FOUR_WEEKS(4);

    val days: Int get() = daysPerChart * FATIGUE_CHART_COUNT
}

/** One calendar day's hourly index. The [trend] is placed on its chart's day, so that days overlay by time of day. */
data class FatigueDayLine(val date: LocalDate, val trend: Trend)

/** One 00:00-23:59 chart: consecutive days, newest first. */
data class FatigueDayChart(val lines: List<FatigueDayLine>)

/** The stored hours of the longest [FatigueHistorySpan], so switching spans needs no new read. */
class FatigueHourlyHistory(hours: List<RollupPoint>, private val today: LocalDate, private val zone: ZoneId) {

    private val hoursByDate = hours.groupBy { it.bucketStart.atZone(zone).toLocalDate() }

    /** The span's days from today backwards, cut into charts of [FatigueHistorySpan.daysPerChart] consecutive days. */
    fun charts(span: FatigueHistorySpan): List<FatigueDayChart> =
        (0L until FATIGUE_CHART_COUNT).map { chart ->
            val chartDay = today.minusDays(chart * span.daysPerChart)
            FatigueDayChart((0L until span.daysPerChart).map { daysBack -> line(chartDay, chartDay.minusDays(daysBack)) })
        }

    private fun line(chartDay: LocalDate, date: LocalDate): FatigueDayLine {
        val points = hoursByDate[date].orEmpty().map { hour ->
            val timeOfDay = hour.bucketStart.atZone(zone).toLocalTime()
            TrendPoint(chartDay.atTime(timeOfDay).atZone(zone).toInstant(), hour.average)
        }
        val start = chartDay.atStartOfDay(zone).toInstant()
        val end = chartDay.atTime(LAST_MINUTE_OF_DAY).atZone(zone).toInstant()
        return FatigueDayLine(date, Trend(points, start, end))
    }
}

fun interface FatigueHistory {
    suspend fun load(): FatigueHourlyHistory
}

/** Reads the HOUR rollups of the points [org.sand.ticwatch.phone.health.FatigueIndexDeriver] stores: days before it ran stay empty. */
class RollupFatigueHistory(
    private val queries: SensorHistoryQueries,
    private val clock: Clock,
    private val zone: ZoneId,
) : FatigueHistory {

    override suspend fun load(): FatigueHourlyHistory {
        val now = clock.instant()
        val today = now.atZone(zone).toLocalDate()
        val from = today.minusDays(FatigueHistorySpan.entries.maxOf { it.days } - 1L).atStartOfDay(zone).toInstant()
        return FatigueHourlyHistory(queries.rollups(SensorType.FATIGUE_INDEX, RollupResolution.HOUR, from, now), today, zone)
    }
}
