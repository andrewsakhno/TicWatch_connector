package org.sand.ticwatch.phone.insights

import androidx.annotation.StringRes
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.advisor.SleepThresholds
import org.sand.ticwatch.phone.sleep.VagalRecoveryStatus
import kotlin.math.roundToInt

private const val PERCENT = 100

/** What the index is made of; [weight] is the component's share when every component has data. */
enum class FatigueComponent(@StringRes val label: Int, val weight: Double) {
    PHYSICAL_LOAD(R.string.fatigue_component_load, 0.3),
    AUTONOMIC_STRAIN(R.string.fatigue_component_autonomic, 0.3),
    PULSE_DEBT(R.string.fatigue_component_pulse, 0.2),
    RECOVERY_DEFICIT(R.string.fatigue_component_recovery, 0.2),
}

/** Fatigue bands, ascending; [maxPercent] is where each ends. */
enum class FatigueLevel(@StringRes val label: Int, val maxPercent: Int) {
    FRESH(R.string.fatigue_level_fresh, 30),
    MODERATE(R.string.fatigue_level_moderate, 60),
    PRONOUNCED(R.string.fatigue_level_pronounced, 80),
    EXHAUSTED(R.string.fatigue_level_exhausted, 100);

    companion object {
        fun of(percent: Int): FatigueLevel = entries.first { percent <= it.maxPercent }
    }
}

/** Index 0..100 with each available [FatigueComponent] as 0..1. */
data class FatigueIndex(val percent: Int, val components: Map<FatigueComponent, Double>) {
    val level: FatigueLevel get() = FatigueLevel.of(percent)

    /** Readiness for physical load: the share of capacity the fatigue hasn't taken. */
    val loadReadinessPercent: Int get() = PERCENT - percent
}

/** The signals behind the index, null where the history has none. */
data class FatigueInputs(
    val steps24h: Double?,
    val trainingLoad24h: Double?,
    val rmssdMs: Double?,
    val rmssdBaselineMs: Double?,
    val restingHeartRateBpm: Double?,
    val heartRateNormBpm: Double?,
    val sleepScore: Double?,
    val vagalRecoveryRatio: Double?,
)

/**
 * Weighted mean of the components that have data, renormalised over them; each is clamped to 0..1. Null when
 * they carry less than [MIN_KNOWN_WEIGHT] of the total, since one signal alone says little about fatigue.
 * Heuristic: the ceilings below are literature-order values, not calibrated against the wearer.
 * - Physical load: the larger of steps / [STEPS_CEILING] and TRIMP / [TRIMP_CEILING] (a workout's steps count in both).
 * - Autonomic strain: the larger of the RMSSD drop below the personal baseline (full at [FULL_RMSSD_DROP]) and
 *   the absolute penalty, rising from 0 at [LOW_RMSSD_START_MS] to full at [LOW_RMSSD_FULL_MS].
 * - Pulse debt: resting heart rate above the hour's norm, full at [FULL_HEART_RATE_EXCESS_BPM].
 * - Recovery deficit: the larger of the sleep score below [SleepThresholds.GOOD_SLEEP_SCORE] (full at
 *   [FULL_DEFICIT_SLEEP_SCORE]) and the vagal ratio below balanced (full at [FULL_VAGAL_SHORTFALL] under it).
 */
class FatigueIndexCalculator {

    fun calculate(inputs: FatigueInputs): FatigueIndex? {
        val components = FatigueComponent.entries
            .mapNotNull { component -> strain(component, inputs)?.let { component to it.coerceIn(0.0, 1.0) } }
            .toMap()
        val knownWeight = components.keys.sumOf { it.weight }
        if (knownWeight < MIN_KNOWN_WEIGHT) return null
        val weighted = components.entries.sumOf { (component, strain) -> component.weight * strain }
        return FatigueIndex((weighted / knownWeight * PERCENT).roundToInt(), components)
    }

    private fun strain(component: FatigueComponent, inputs: FatigueInputs): Double? = when (component) {
        FatigueComponent.PHYSICAL_LOAD -> listOfNotNull(
            inputs.steps24h?.let { it / STEPS_CEILING },
            inputs.trainingLoad24h?.let { it / TRIMP_CEILING },
        ).maxOrNull()
        FatigueComponent.AUTONOMIC_STRAIN -> inputs.rmssdMs?.let { autonomicStrain(it, inputs.rmssdBaselineMs) }
        FatigueComponent.PULSE_DEBT -> pulseDebt(inputs.restingHeartRateBpm, inputs.heartRateNormBpm)
        FatigueComponent.RECOVERY_DEFICIT -> listOfNotNull(
            inputs.sleepScore?.let { (SleepThresholds.GOOD_SLEEP_SCORE - it) / (SleepThresholds.GOOD_SLEEP_SCORE - FULL_DEFICIT_SLEEP_SCORE) },
            inputs.vagalRecoveryRatio?.let { (VagalRecoveryStatus.BALANCED.minRatio - it) / FULL_VAGAL_SHORTFALL },
        ).maxOrNull()
    }

    private fun autonomicStrain(rmssdMs: Double, baselineMs: Double?): Double {
        val lowPenalty = (LOW_RMSSD_START_MS - rmssdMs) / (LOW_RMSSD_START_MS - LOW_RMSSD_FULL_MS)
        val drop = baselineMs?.takeIf { it > 0 }?.let { (1 - rmssdMs / it) / FULL_RMSSD_DROP } ?: 0.0
        return maxOf(lowPenalty, drop)
    }

    private fun pulseDebt(restingBpm: Double?, normBpm: Double?): Double? {
        if (restingBpm == null || normBpm == null) return null
        return (restingBpm - normBpm) / FULL_HEART_RATE_EXCESS_BPM
    }

    private companion object {
        const val MIN_KNOWN_WEIGHT = 0.5
        const val STEPS_CEILING = 20_000.0
        const val TRIMP_CEILING = 200.0
        const val FULL_RMSSD_DROP = 0.5
        const val LOW_RMSSD_START_MS = 30.0
        const val LOW_RMSSD_FULL_MS = 25.0
        const val FULL_HEART_RATE_EXCESS_BPM = 20.0
        const val FULL_DEFICIT_SLEEP_SCORE = 40.0
        const val FULL_VAGAL_SHORTFALL = 0.3
    }
}
