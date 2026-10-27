package org.sand.ticwatch.phone.insights

import java.time.Instant

/** A closed-open time span [start, end) excluded from a daily summary. */
data class TimeSpan(val start: Instant, val end: Instant) {
    operator fun contains(at: Instant): Boolean = !at.isBefore(start) && at.isBefore(end)
}

/** Share (0..1) of a day's measured awake, still readings at each [StressLevel]. */
data class DailyStressSummary(val shares: Map<StressLevel, Double>, val measurements: Int) {
    fun share(level: StressLevel): Double = shares[level] ?: 0.0
}

/**
 * Classifies each RMSSD reading by [StressLevel] and counts shares, skipping readings inside [excluded]
 * spans (sleep, where RMSSD is recovery rather than daytime stress, and walking, where it is effort).
 * Null when nothing is left to classify.
 */
class DailyStressCalculator {

    fun summarize(readings: List<TrendPoint>, excluded: List<TimeSpan>): DailyStressSummary? {
        val kept = readings.filter { reading -> excluded.none { reading.at in it } }
        if (kept.isEmpty()) return null
        val counts = kept.groupingBy { StressLevel.of(it.value) }.eachCount()
        return DailyStressSummary(counts.mapValues { (_, count) -> count.toDouble() / kept.size }, kept.size)
    }
}
