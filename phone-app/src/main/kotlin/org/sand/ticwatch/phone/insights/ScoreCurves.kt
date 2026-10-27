package org.sand.ticwatch.phone.insights

/** Piecewise-linear 0..1 mappings shared by the scorers, so every score component reads the same way. */
object ScoreCurves {

    /** 0 at [worst], 1 at [best], clamped; works for either direction of the scale. */
    fun linear(value: Double, worst: Double, best: Double): Double =
        ((value - worst) / (best - worst)).coerceIn(0.0, 1.0)

    /** 1 inside [idealLow]..[idealHigh], falling linearly to 0 at [worstLow] and [worstHigh]. */
    fun plateau(value: Double, worstLow: Double, idealLow: Double, idealHigh: Double, worstHigh: Double): Double = when {
        value < idealLow -> linear(value, worstLow, idealLow)
        value > idealHigh -> linear(value, worstHigh, idealHigh)
        else -> 1.0
    }
}
