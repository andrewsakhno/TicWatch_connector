package org.sand.ticwatch.phone.insights

import androidx.annotation.StringRes
import java.time.LocalDate
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.HeartRateZone

/** Acute:chronic workload ratio bands; [maxRatio] is where each ends. */
enum class LoadBalance(@StringRes val label: Int, val maxRatio: Double) {
    LOW(R.string.load_balance_low, 0.8),
    OPTIMAL(R.string.load_balance_optimal, 1.3),
    HIGH(R.string.load_balance_high, 1.5),
    VERY_HIGH(R.string.load_balance_very_high, Double.POSITIVE_INFINITY);

    companion object {
        fun of(ratio: Double): LoadBalance = entries.first { ratio <= it.maxRatio }
    }
}

/** [date]'s cardio load (TRIMP): the day itself, the acute 7-day sum and the chronic 28-day weekly mean. */
data class TrainingLoadBalance(
    val date: LocalDate,
    val dayLoad: Double,
    val acuteLoad: Double,
    val chronicWeeklyLoad: Double,
    val ratio: Double?,
    val balance: LoadBalance?,
) {
    /** [ratio] kept inside [balance]'s band, for gauges: a ratio the calculator didn't accept as overreaching must not read as such. */
    val gaugeRatio: Double? get() = ratio?.let { value -> balance?.let { value.coerceAtMost(it.maxRatio) } ?: value }
}

/**
 * Acute (last [acuteDays]) vs chronic (weekly mean of the last [chronicDays]) load; no ratio without chronic load.
 * A ratio in the [LoadBalance.VERY_HIGH] band only counts as overreaching with at least [minOverreachingAcuteLoad]
 * and at most [maxOverreachingEasyShare] of the acute zone minutes in [EASY_ZONES]; otherwise it is
 * [LoadBalance.OPTIMAL]: the chronic mean of a new or returning wearer makes any light week look like a spike.
 */
class TrainingLoadBalanceCalculator(
    val acuteDays: Long = 7,
    private val chronicDays: Long = 28,
    private val minOverreachingAcuteLoad: Double = 150.0,
    private val maxOverreachingEasyShare: Double = 0.85,
) {

    fun balance(
        date: LocalDate,
        dailyLoads: Map<LocalDate, Double>,
        dailyZoneMinutes: Map<LocalDate, Map<HeartRateZone, Double>> = emptyMap(),
    ): TrainingLoadBalance {
        val acute = dailyLoads.inWindow(date, acuteDays).sum()
        val chronic = dailyLoads.inWindow(date, chronicDays).sum() / (chronicDays.toDouble() / acuteDays)
        val ratio = if (chronic > 0) acute / chronic else null
        val balance = ratio?.let { classify(it, acute, dailyZoneMinutes.inWindow(date, acuteDays)) }
        return TrainingLoadBalance(date, dailyLoads[date] ?: 0.0, acute, chronic, ratio, balance)
    }

    private fun classify(ratio: Double, acute: Double, zoneMinutes: Collection<Map<HeartRateZone, Double>>): LoadBalance {
        val band = LoadBalance.of(ratio)
        if (band != LoadBalance.VERY_HIGH) return band
        val mostlyEasy = (easyShare(zoneMinutes) ?: 0.0) > maxOverreachingEasyShare
        return if (acute < minOverreachingAcuteLoad || mostlyEasy) LoadBalance.OPTIMAL else band
    }

    /** Share of the minutes spent in [EASY_ZONES], null without any zone minutes. */
    private fun easyShare(zoneMinutes: Collection<Map<HeartRateZone, Double>>): Double? {
        val minutes = zoneMinutes.flatMap { it.entries }
        val total = minutes.sumOf { it.value }
        return if (total > 0) minutes.filter { it.key in EASY_ZONES }.sumOf { it.value } / total else null
    }

    private fun <T> Map<LocalDate, T>.inWindow(date: LocalDate, days: Long): Collection<T> {
        val first = date.minusDays(days - 1)
        return filterKeys { !it.isBefore(first) && !it.isAfter(date) }.values
    }

    private companion object {
        val EASY_ZONES = setOf(HeartRateZone.ZONE_0, HeartRateZone.ZONE_1, HeartRateZone.ZONE_2)
    }
}
