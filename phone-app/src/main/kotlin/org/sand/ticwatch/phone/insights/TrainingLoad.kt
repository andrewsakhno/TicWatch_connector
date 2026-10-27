package org.sand.ticwatch.phone.insights

import java.time.LocalDate

/** Acute:chronic workload ratio bands; [maxRatio] is where each ends. */
enum class LoadBalance(val label: String, val maxRatio: Double) {
    LOW("Detraining", 0.8),
    OPTIMAL("Optimal", 1.3),
    HIGH("High", 1.5),
    VERY_HIGH("Overreaching", Double.POSITIVE_INFINITY);

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
)

/** Acute (last [acuteDays]) vs chronic (weekly mean of the last [chronicDays]) load; no ratio without chronic load. */
class TrainingLoadBalanceCalculator(private val acuteDays: Long = 7, private val chronicDays: Long = 28) {

    fun balance(date: LocalDate, dailyLoads: Map<LocalDate, Double>): TrainingLoadBalance {
        val acute = sum(dailyLoads, date, acuteDays)
        val chronic = sum(dailyLoads, date, chronicDays) / (chronicDays.toDouble() / acuteDays)
        val ratio = if (chronic > 0) acute / chronic else null
        return TrainingLoadBalance(date, dailyLoads[date] ?: 0.0, acute, chronic, ratio, ratio?.let(LoadBalance::of))
    }

    private fun sum(dailyLoads: Map<LocalDate, Double>, date: LocalDate, days: Long): Double {
        val first = date.minusDays(days - 1)
        return dailyLoads.filterKeys { !it.isBefore(first) && !it.isAfter(date) }.values.sum()
    }
}
