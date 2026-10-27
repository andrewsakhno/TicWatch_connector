package org.sand.ticwatch.phone.insights

import org.sand.ticwatch.phone.insights.ScoreCurves.linear
import kotlin.math.abs
import kotlin.math.roundToInt

/** Inputs of [ReadinessScorer]; baselines are the personal norms the night is compared against. */
data class ReadinessInputs(
    val nightlyRmssdMs: Double?,
    val baselineRmssdMs: Double?,
    val restingHeartRateBpm: Double?,
    val baselineRestingHeartRateBpm: Double?,
    val skinTemperatureDeltaCelsius: Double?,
)

/**
 * Averages the available components, each mapped linearly to 0..1: RMSSD as a ratio to its baseline
 * (or absolute ms before a baseline exists), resting HR as the rise over its baseline, skin temperature
 * as the absolute nightly deviation. Null when no component has data. Heuristic, not clinically validated.
 */
class ReadinessScorer {

    fun score(inputs: ReadinessInputs): Readiness? {
        val components = listOfNotNull(rmssdComponent(inputs), restingHeartRateComponent(inputs), skinComponent(inputs))
        if (components.isEmpty()) return null
        val score = (components.average() * MAX_SCORE).roundToInt()
        return Readiness(
            score = score,
            status = ReadinessStatus.of(score),
            nightlyRmssdMs = inputs.nightlyRmssdMs,
            restingHeartRateBpm = inputs.restingHeartRateBpm,
            skinTemperatureDeltaCelsius = inputs.skinTemperatureDeltaCelsius,
        )
    }

    private fun rmssdComponent(inputs: ReadinessInputs): Double? {
        val nightly = inputs.nightlyRmssdMs ?: return null
        val baseline = inputs.baselineRmssdMs
        return if (baseline != null && baseline > 0) {
            linear(nightly / baseline, RMSSD_RATIO_WORST, RMSSD_RATIO_BEST)
        } else {
            linear(nightly, RMSSD_ABSOLUTE_WORST_MS, RMSSD_ABSOLUTE_BEST_MS)
        }
    }

    private fun restingHeartRateComponent(inputs: ReadinessInputs): Double? {
        val resting = inputs.restingHeartRateBpm ?: return null
        val baseline = inputs.baselineRestingHeartRateBpm ?: return null
        return linear(resting - baseline, RESTING_HR_RISE_WORST_BPM, RESTING_HR_RISE_BEST_BPM)
    }

    private fun skinComponent(inputs: ReadinessInputs): Double? {
        val delta = inputs.skinTemperatureDeltaCelsius ?: return null
        return linear(abs(delta), SKIN_DELTA_WORST_CELSIUS, SKIN_DELTA_BEST_CELSIUS)
    }

    private companion object {
        const val MAX_SCORE = 100
        const val RMSSD_RATIO_WORST = 0.7
        const val RMSSD_RATIO_BEST = 1.1
        const val RMSSD_ABSOLUTE_WORST_MS = 20.0
        const val RMSSD_ABSOLUTE_BEST_MS = 60.0
        const val RESTING_HR_RISE_WORST_BPM = 8.0
        const val RESTING_HR_RISE_BEST_BPM = -2.0
        const val SKIN_DELTA_WORST_CELSIUS = 1.0
        const val SKIN_DELTA_BEST_CELSIUS = 0.0
    }
}
