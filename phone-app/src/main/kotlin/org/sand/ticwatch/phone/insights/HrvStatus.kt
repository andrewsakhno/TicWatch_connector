package org.sand.ticwatch.phone.insights

import java.time.LocalDate
import kotlin.math.sqrt

/** One night's mean RMSSD, keyed by the local date the night ended on. */
data class NightlyValue(val date: LocalDate, val value: Double)

/** Garmin-style HRV status of the 7-day nightly RMSSD mean against the personal [HrvBaseline]. */
enum class HrvStatusLevel(val label: String) {
    BALANCED("Balanced"),
    UNBALANCED("Unbalanced"),
    LOW("Low"),
    POOR("Poor"),
}

/** The personal norm corridor [low]..[high] (mean ± the band factor times SD); below [veryLow] reads as LOW. */
data class HrvBaseline(
    val mean: Double,
    val standardDeviation: Double,
    val low: Double,
    val high: Double,
    val veryLow: Double,
    val nights: Int,
)

/** [level] and [baseline] are null until enough nights exist; [weeklyMean] null without nights this week. */
data class HrvStatus(
    val date: LocalDate,
    val weeklyMean: Double?,
    val weeklyNights: Int,
    val baseline: HrvBaseline?,
    val level: HrvStatusLevel?,
)

/**
 * Classifies [date]'s 7-day nightly RMSSD mean against the [baselineDays]-day corridor mean ± [bandFactor] SD:
 * inside is [HrvStatusLevel.BALANCED], outside [HrvStatusLevel.UNBALANCED], more than [lowFactor] SD below
 * the mean [HrvStatusLevel.LOW], and under the absolute [poorRmssdMs] floor [HrvStatusLevel.POOR] whatever
 * the baseline. Pure and deterministic; the nights come in any order, duplicates per date are averaged.
 */
class HrvStatusClassifier(
    private val weekDays: Long = 7,
    private val baselineDays: Long = 28,
    private val bandFactor: Double = 0.75,
    private val lowFactor: Double = 1.5,
    private val minWeekNights: Int = 3,
    private val minBaselineNights: Int = 14,
    private val poorRmssdMs: Double = 20.0,
) {

    fun classify(date: LocalDate, nights: List<NightlyValue>): HrvStatus {
        val perDay = nights.groupBy { it.date }.mapValues { (_, values) -> values.map { it.value }.average() }
        val week = window(perDay, date, weekDays)
        val weeklyMean = week.takeIf { it.isNotEmpty() }?.average()
        val baseline = baseline(window(perDay, date, baselineDays))
        val level = if (weeklyMean == null || week.size < minWeekNights || baseline == null) null else level(weeklyMean, baseline)
        return HrvStatus(date, weeklyMean, week.size, baseline, level)
    }

    private fun window(perDay: Map<LocalDate, Double>, date: LocalDate, days: Long): List<Double> {
        val first = date.minusDays(days - 1)
        return perDay.filterKeys { !it.isBefore(first) && !it.isAfter(date) }.values.toList()
    }

    private fun baseline(values: List<Double>): HrvBaseline? {
        if (values.size < minBaselineNights) return null
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        return HrvBaseline(mean, sd, mean - bandFactor * sd, mean + bandFactor * sd, mean - lowFactor * sd, values.size)
    }

    private fun level(weeklyMean: Double, baseline: HrvBaseline): HrvStatusLevel = when {
        weeklyMean < poorRmssdMs -> HrvStatusLevel.POOR
        weeklyMean < baseline.veryLow -> HrvStatusLevel.LOW
        weeklyMean < baseline.low || weeklyMean > baseline.high -> HrvStatusLevel.UNBALANCED
        else -> HrvStatusLevel.BALANCED
    }
}
