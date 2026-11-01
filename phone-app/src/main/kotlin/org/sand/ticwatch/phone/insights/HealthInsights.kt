package org.sand.ticwatch.phone.insights

import androidx.annotation.StringRes
import java.time.Duration
import java.time.Instant
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.sleep.SleepAdvancedInsights

/** Snapshot of the Dashboard's "Metrics" tab, each card null when its data isn't in the history yet. */
data class HealthInsights(
    val readiness: Readiness?,
    val stress: Stress?,
    val sleep: SleepInsight?,
    val barometer: Barometer?,
    val light: Light?,
    val trends: InsightTrends,
)

data class TrendPoint(val at: Instant, val value: Double)

/** Values of one metric over [start]..[end], oldest first, for a card's mini graph. */
data class Trend(val points: List<TrendPoint>, val start: Instant, val end: Instant) {

    /** The value the drawn line passes through at [at]; null before the first point and after the last. */
    fun valueAt(at: Instant): Double? {
        val nextIndex = points.indexOfFirst { !it.at.isBefore(at) }
        if (nextIndex < 0) return null
        val next = points[nextIndex]
        if (next.at == at) return next.value
        val previous = points.getOrNull(nextIndex - 1) ?: return null
        val fraction = Duration.between(previous.at, at).toMillis().toDouble() / Duration.between(previous.at, next.at).toMillis()
        return previous.value + (next.value - previous.value) * fraction
    }
}

/** One [Trend] per card; empty points when the history has nothing in that span yet. */
data class InsightTrends(
    val readiness: Trend,
    val stress: Trend,
    val sleep: Trend,
    val barometer: Trend,
    val light: Trend,
)

/** Daily readiness 0-100 from last night's RMSSD, resting HR and skin-temperature delta vs. personal baselines. */
data class Readiness(
    val score: Int,
    val status: ReadinessStatus,
    val nightlyRmssdMs: Double?,
    val restingHeartRateBpm: Double?,
    val skinTemperatureDeltaCelsius: Double?,
)

enum class ReadinessStatus(@StringRes val label: Int, val minScore: Int) {
    RECOVERED(R.string.readiness_status_recovered, 70),
    NORMAL(R.string.readiness_status_normal, 40),
    REST_NEEDED(R.string.readiness_status_rest_needed, 0);

    companion object {
        fun of(score: Int): ReadinessStatus = entries.first { score >= it.minScore }
    }
}

data class Stress(val rmssdMs: Double, val level: StressLevel, val at: Instant)

/** Stress from a resting RMSSD: higher vagal tone (RMSSD) means lower stress; [REST] is a relaxed state. */
enum class StressLevel(@StringRes val label: Int, val minRmssdMs: Double) {
    REST(R.string.stress_rest, 70.0),
    LOW(R.string.stress_low, 50.0),
    MODERATE(R.string.stress_moderate, 30.0),
    HIGH(R.string.stress_high, 0.0);

    companion object {
        fun of(rmssdMs: Double): StressLevel = entries.first { rmssdMs >= it.minRmssdMs }
    }
}

data class SleepInsight(
    val start: Instant,
    val end: Instant,
    val stageDurations: Map<SleepStageType, Duration>,
    val desaturationIndexPerHour: Double?,
    val snoringMinutes: Double?,
    val advanced: SleepAdvancedInsights?,
) {
    val duration: Duration get() = Duration.between(start, end)

    /** Share of the staged time spent in [stage], or null when the night has no stages. */
    fun share(stage: SleepStageType): Double? {
        val staged = stageDurations.values.fold(Duration.ZERO, Duration::plus)
        if (staged.isZero) return null
        return (stageDurations[stage] ?: Duration.ZERO).toMillis().toDouble() / staged.toMillis()
    }
}

data class Barometer(val currentHpa: Double, val dayChangeHpa: Double?, val trend: PressureTrend?)

/** Day-over-day pressure change; [SHARP_FALL]/[SHARP_RISE] are the swings weather-sensitive people notice. */
enum class PressureTrend(@StringRes val label: Int, val minChangeHpa: Double) {
    SHARP_RISE(R.string.pressure_trend_sharp_rise, 6.0),
    RISING(R.string.pressure_trend_rising, 1.0),
    STABLE(R.string.pressure_trend_stable, -1.0),
    FALLING(R.string.pressure_trend_falling, -6.0),
    SHARP_FALL(R.string.pressure_trend_sharp_fall, Double.NEGATIVE_INFINITY);

    companion object {
        fun of(changeHpa: Double): PressureTrend = entries.first { changeHpa >= it.minChangeHpa }
    }
}

data class Light(val lux: Double, val context: LightContext, val at: Instant)

enum class LightContext(@StringRes val label: Int, val minLux: Double) {
    OUTDOOR(R.string.light_outdoor, 1_000.0),
    INDOOR(R.string.light_indoor, 10.0),
    DARK(R.string.light_dark, 0.0);

    companion object {
        fun of(lux: Double): LightContext = entries.first { lux >= it.minLux }
    }
}
