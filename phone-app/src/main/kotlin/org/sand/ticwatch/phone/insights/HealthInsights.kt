package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.SleepStageType
import java.time.Duration
import java.time.Instant

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
data class Trend(val points: List<TrendPoint>, val start: Instant, val end: Instant)

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

enum class ReadinessStatus(val label: String, val minScore: Int) {
    RECOVERED("Recovered", 70),
    NORMAL("Normal", 40),
    REST_NEEDED("Rest needed", 0);

    companion object {
        fun of(score: Int): ReadinessStatus = entries.first { score >= it.minScore }
    }
}

data class Stress(val rmssdMs: Double, val level: StressLevel, val at: Instant)

/** Stress from a resting RMSSD: higher vagal tone (RMSSD) means lower stress; [REST] is a relaxed state. */
enum class StressLevel(val label: String, val minRmssdMs: Double) {
    REST("Rest", 70.0),
    LOW("Low", 50.0),
    MODERATE("Medium", 30.0),
    HIGH("High", 0.0);

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
enum class PressureTrend(val label: String, val minChangeHpa: Double) {
    SHARP_RISE("Sharp rise", 6.0),
    RISING("Rising", 1.0),
    STABLE("Stable", -1.0),
    FALLING("Falling", -6.0),
    SHARP_FALL("Sharp fall", Double.NEGATIVE_INFINITY);

    companion object {
        fun of(changeHpa: Double): PressureTrend = entries.first { changeHpa >= it.minChangeHpa }
    }
}

data class Light(val lux: Double, val context: LightContext, val at: Instant)

enum class LightContext(val label: String, val minLux: Double) {
    OUTDOOR("Outdoor", 1_000.0),
    INDOOR("Indoor", 10.0),
    DARK("Dark", 0.0);

    companion object {
        fun of(lux: Double): LightContext = entries.first { lux >= it.minLux }
    }
}
