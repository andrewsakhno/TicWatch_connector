package org.sand.ticwatch.phone.insights

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Morning time spent in bright light, against [goal]. */
data class MorningLightExposure(val date: LocalDate, val bright: Duration, val goal: Duration) {
    val goalReached: Boolean get() = bright >= goal
}

/**
 * Circadian "morning light": time in [from, to) local at >= [brightLux] (outdoor daylight). The watch
 * measures light in windows, so each reading stands for the time until the next one, at most
 * [maxReadingSpan] (the measuring period), clipped to the morning.
 */
class CircadianLightExposureCalculator(
    private val zone: ZoneId,
    private val from: LocalTime = LocalTime.of(6, 0),
    private val to: LocalTime = LocalTime.of(12, 0),
    private val brightLux: Double = LightContext.OUTDOOR.minLux,
    private val maxReadingSpan: Duration = Duration.ofMinutes(30),
    private val goal: Duration = Duration.ofMinutes(30),
) {

    /** First instant whose readings can reach into [date]'s morning. */
    fun readFrom(date: LocalDate): Instant = date.atTime(from).atZone(zone).toInstant().minus(maxReadingSpan)

    fun readTo(date: LocalDate): Instant = date.atTime(to).atZone(zone).toInstant()

    fun exposure(date: LocalDate, readings: List<TrendPoint>): MorningLightExposure {
        val start = date.atTime(from).atZone(zone).toInstant()
        val end = readTo(date)
        val sorted = readings.sortedBy { it.at }
        var bright = Duration.ZERO
        sorted.forEachIndexed { index, reading ->
            if (reading.value < brightLux) return@forEachIndexed
            val next = sorted.getOrNull(index + 1)?.at ?: end
            val covered = minOf(next, reading.at.plus(maxReadingSpan), end)
            val begin = maxOf(reading.at, start)
            if (covered.isAfter(begin)) bright += Duration.between(begin, covered)
        }
        return MorningLightExposure(date, bright, goal)
    }
}
