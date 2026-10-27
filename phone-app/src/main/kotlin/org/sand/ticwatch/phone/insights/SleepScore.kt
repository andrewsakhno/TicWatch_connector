package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.insights.ScoreCurves.linear
import org.sand.ticwatch.phone.insights.ScoreCurves.plateau
import java.time.Duration
import kotlin.math.roundToInt

/** One weighted part of the [SleepScore]; [key] is how the MCP tool reports it. */
enum class SleepScoreComponent(val key: String, val weight: Double) {
    DURATION("duration", 0.30),
    ARCHITECTURE("stage_architecture", 0.25),
    EFFICIENCY("efficiency", 0.15),
    RELAXATION("heart_rate_relaxation", 0.15),
    BREATHING("breathing", 0.15),
}

/** What one night is scored on; nulls are components without data that night. */
data class SleepScoreInputs(
    val inBed: Duration,
    val stageDurations: Map<SleepStageType, Duration>,
    val awakenings: Int,
    val sleepHeartRateBpm: Double?,
    val daytimeHeartRateBpm: Double?,
    val desaturationIndexPerHour: Double?,
) {
    private val awake: Duration get() = stageDurations[SleepStageType.AWAKE] ?: Duration.ZERO
    private val asleepStaged: Duration
        get() = stageDurations.filterKeys { it != SleepStageType.AWAKE }.values.fold(Duration.ZERO, Duration::plus)

    val asleep: Duration get() = (inBed - awake).coerceAtLeast(Duration.ZERO)

    /** Asleep share of the time in bed, or null when the night has no stages to tell awake from asleep. */
    val efficiency: Double? get() = if (stageDurations.isEmpty() || inBed.isZero) null else asleep.toMillis().toDouble() / inBed.toMillis()

    /** Share of the staged asleep time in [stage], or null without stages that tell light/deep/REM apart. */
    fun asleepShare(stage: SleepStageType): Double? {
        if (asleepStaged.isZero || stageDurations.keys.none { it in ARCHITECTURE_STAGES }) return null
        return (stageDurations[stage] ?: Duration.ZERO).toMillis().toDouble() / asleepStaged.toMillis()
    }

    /** Nocturnal heart-rate dip: how far the night's mean HR falls below the day's, as a fraction. */
    val heartRateDip: Double?
        get() {
            val night = sleepHeartRateBpm ?: return null
            val day = daytimeHeartRateBpm?.takeIf { it > 0 } ?: return null
            return 1 - night / day
        }

    private companion object {
        val ARCHITECTURE_STAGES = setOf(SleepStageType.LIGHT, SleepStageType.DEEP, SleepStageType.REM)
    }
}

/** Night score 0..100 with each available [SleepScoreComponent] as 0..1. */
data class SleepScore(val score: Int, val components: Map<SleepScoreComponent, Double>, val inputs: SleepScoreInputs)

/** Sleep score bands; [minScore] is where each starts. */
enum class SleepQuality(val label: String, val minScore: Int) {
    EXCELLENT("Excellent", 90),
    GOOD("Good", 80),
    FAIR("Fair", 60),
    POOR("Poor", 0);

    companion object {
        fun of(score: Int): SleepQuality = entries.first { score >= it.minScore }
    }
}

/**
 * Firstbeat/Samsung-style night score: the weighted mean of the components that have data, each mapped to
 * 0..1 - duration against a 7.5-8.5 h target, deep (15-25%) and REM (20-25%) shares of the asleep time,
 * efficiency (asleep / in bed), the nocturnal heart-rate dip below the daytime mean (>= 10% is a normal
 * "dipper") and the oxygen desaturation index. Heuristic, not clinically validated.
 */
class SleepScoreCalculator {

    fun score(inputs: SleepScoreInputs): SleepScore {
        val components = buildMap {
            put(SleepScoreComponent.DURATION, duration(inputs))
            architecture(inputs)?.let { put(SleepScoreComponent.ARCHITECTURE, it) }
            inputs.efficiency?.let { put(SleepScoreComponent.EFFICIENCY, linear(it, EFFICIENCY_WORST, EFFICIENCY_BEST)) }
            inputs.heartRateDip?.let { put(SleepScoreComponent.RELAXATION, linear(it, HR_DIP_WORST, HR_DIP_BEST)) }
            inputs.desaturationIndexPerHour?.let { put(SleepScoreComponent.BREATHING, linear(it, ODI_WORST, ODI_BEST)) }
        }
        val weighted = components.entries.sumOf { (component, value) -> component.weight * value }
        val weights = components.keys.sumOf { it.weight }
        return SleepScore((weighted / weights * MAX_SCORE).roundToInt(), components, inputs)
    }

    private fun duration(inputs: SleepScoreInputs): Double =
        plateau(inputs.asleep.toMillis() / MILLIS_PER_HOUR, DURATION_WORST_LOW_H, DURATION_IDEAL_LOW_H, DURATION_IDEAL_HIGH_H, DURATION_WORST_HIGH_H)

    private fun architecture(inputs: SleepScoreInputs): Double? {
        val deep = inputs.asleepShare(SleepStageType.DEEP) ?: return null
        val rem = inputs.asleepShare(SleepStageType.REM) ?: return null
        return (plateau(deep, SHARE_WORST_LOW, DEEP_IDEAL_LOW, DEEP_IDEAL_HIGH, SHARE_WORST_HIGH) +
            plateau(rem, SHARE_WORST_LOW, REM_IDEAL_LOW, REM_IDEAL_HIGH, SHARE_WORST_HIGH)) / 2
    }

    private companion object {
        const val MAX_SCORE = 100
        const val MILLIS_PER_HOUR = 3_600_000.0
        const val DURATION_WORST_LOW_H = 4.5
        const val DURATION_IDEAL_LOW_H = 7.5
        const val DURATION_IDEAL_HIGH_H = 8.5
        const val DURATION_WORST_HIGH_H = 11.0
        const val SHARE_WORST_LOW = 0.0
        const val SHARE_WORST_HIGH = 0.40
        const val DEEP_IDEAL_LOW = 0.15
        const val DEEP_IDEAL_HIGH = 0.25
        const val REM_IDEAL_LOW = 0.20
        const val REM_IDEAL_HIGH = 0.25
        const val EFFICIENCY_WORST = 0.70
        const val EFFICIENCY_BEST = 0.90
        const val HR_DIP_WORST = 0.0
        const val HR_DIP_BEST = 0.10
        const val ODI_WORST = 15.0
        const val ODI_BEST = 5.0
    }
}
