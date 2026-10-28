package org.sand.ticwatch.phone.advisor

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
}
