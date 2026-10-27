package org.sand.ticwatch.phone.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HrvStatusClassifierTest {

    private val classifier = HrvStatusClassifier()
    private val today = LocalDate.of(2026, 10, 27)

    @Test
    fun `week inside the corridor is balanced`() {
        assertEquals(HrvStatusLevel.BALANCED, classify(weekMs = 50.0))
    }

    @Test
    fun `week above the corridor is unbalanced`() {
        assertEquals(HrvStatusLevel.UNBALANCED, classify(weekMs = 58.0))
    }

    @Test
    fun `week far below the mean is low`() {
        assertEquals(HrvStatusLevel.LOW, classify(weekMs = 30.0))
    }

    @Test
    fun `week under the absolute floor is poor`() {
        val nights = (0L until 28L).map { NightlyValue(today.minusDays(it), 15.0) }

        assertEquals(HrvStatusLevel.POOR, classifier.classify(today, nights).level)
    }

    @Test
    fun `no status before the baseline has enough nights`() {
        val nights = (0L until 10L).map { NightlyValue(today.minusDays(it), 50.0) }
        val status = classifier.classify(today, nights)

        assertNull(status.level)
        assertNull(status.baseline)
        assertEquals(50.0, status.weeklyMean!!, DELTA)
    }

    /** 21 earlier nights alternating 48/52 ms, then the last 7 at [weekMs]. */
    private fun classify(weekMs: Double): HrvStatusLevel? {
        val nights = (0L until 28L).map { daysAgo ->
            val value = if (daysAgo < 7) weekMs else if (daysAgo % 2 == 0L) 48.0 else 52.0
            NightlyValue(today.minusDays(daysAgo), value)
        }
        return classifier.classify(today, nights).level
    }

    private companion object {
        const val DELTA = 1e-9
    }
}
