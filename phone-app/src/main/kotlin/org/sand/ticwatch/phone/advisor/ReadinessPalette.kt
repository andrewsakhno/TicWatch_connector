package org.sand.ticwatch.phone.advisor

import org.sand.ticwatch.phone.insights.FatigueLevel

/** ARGB colour of each readiness band, shared by the Advisor tab and its home-screen widget. */
object ReadinessPalette {
    private const val GREEN = 0xFF43A047L
    private const val YELLOW = 0xFFFDD835L
    private const val ORANGE = 0xFFFB8C00L
    private const val RED = 0xFFE53935L

    fun argb(state: ReadinessState): Long = when (state) {
        ReadinessState.OPTIMAL -> GREEN
        ReadinessState.MODERATE -> YELLOW
        ReadinessState.LIMITED -> ORANGE
        ReadinessState.UNLOAD -> RED
    }

    /** Fatigue bands in the same palette: fresh = optimal ... exhausted = unload. */
    fun argb(level: FatigueLevel): Long = argb(
        when (level) {
            FatigueLevel.FRESH -> ReadinessState.OPTIMAL
            FatigueLevel.MODERATE -> ReadinessState.MODERATE
            FatigueLevel.PRONOUNCED -> ReadinessState.LIMITED
            FatigueLevel.EXHAUSTED -> ReadinessState.UNLOAD
        },
    )

    /** Nervous tone in the same palette; the post-stress window is amber like a limited readiness. */
    fun argb(tone: AutonomicTone): Long = argb(
        when (tone) {
            AutonomicTone.CALM -> ReadinessState.OPTIMAL
            AutonomicTone.RECOVERING -> ReadinessState.MODERATE
            AutonomicTone.STRAINED, AutonomicTone.POST_STRESS -> ReadinessState.LIMITED
        },
    )
}
