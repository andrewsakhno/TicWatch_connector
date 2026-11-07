package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.HeartRateZone
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/** Zone minutes of the stored workouts over a date span, today included. */
interface ZoneMinutesHistory {
    /** Minutes per zone summed over [from]..[to]; zones never reached are left out. */
    suspend fun zoneMinutes(from: LocalDate, to: LocalDate): Map<HeartRateZone, Double>

    /** Minutes per zone of every day in [from]..[to] that had a workout. */
    suspend fun dailyZoneMinutes(from: LocalDate, to: LocalDate): Map<LocalDate, Map<HeartRateZone, Double>>
}

/** The zone minutes of one chart: all workouts of [from]..[to]. */
data class ZoneMinutesBucket(val from: LocalDate, val to: LocalDate, val minutes: Map<HeartRateZone, Double>)

/** How the workouts of a period are split into charts, newest first. */
enum class ZoneGrouping {
    /** One chart per calendar day, days without workouts included. */
    DAILY {
        override suspend fun buckets(history: ZoneMinutesHistory, from: LocalDate, to: LocalDate): List<ZoneMinutesBucket> {
            val days = history.dailyZoneMinutes(from, to)
            return generateSequence(to) { it.minusDays(1) }.takeWhile { !it.isBefore(from) }
                .map { day -> ZoneMinutesBucket(day, day, days[day].orEmpty()) }
                .toList()
        }
    },

    /** A single chart of the whole period. */
    TOTAL {
        override suspend fun buckets(history: ZoneMinutesHistory, from: LocalDate, to: LocalDate): List<ZoneMinutesBucket> =
            listOf(ZoneMinutesBucket(from, to, history.zoneMinutes(from, to)))
    };

    abstract suspend fun buckets(history: ZoneMinutesHistory, from: LocalDate, to: LocalDate): List<ZoneMinutesBucket>
}

/** A span ending today and the way its workouts are charted. */
enum class WorkoutPeriod(val span: Period, val grouping: ZoneGrouping) {
    WEEK(Period.ofDays(WEEK_DAYS.toInt()), ZoneGrouping.DAILY),
    MONTH(Period.ofMonths(1), ZoneGrouping.TOTAL),
    QUARTER(Period.ofMonths(3), ZoneGrouping.TOTAL),
    ;

    /** First day of the period ending on [today]: [today] counts as one of its days. */
    fun startFor(today: LocalDate): LocalDate = today.minus(span).plusDays(1)
}

fun interface WorkoutPeriodZones {
    suspend fun buckets(period: WorkoutPeriod): List<ZoneMinutesBucket>
}

class HistoryWorkoutPeriodZones(
    private val history: ZoneMinutesHistory,
    private val clock: Clock,
    private val zone: ZoneId,
) : WorkoutPeriodZones {
    override suspend fun buckets(period: WorkoutPeriod): List<ZoneMinutesBucket> {
        val today = clock.instant().atZone(zone).toLocalDate()
        return period.grouping.buckets(history, period.startFor(today), today)
    }
}
