package org.sand.ticwatch.phone.advisor

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class AutonomicBalanceTest {

    private val calculator = AutonomicBalanceCalculator()

    private fun inputs(
        rmssdMs: Double? = null,
        heartRateBpm: Double? = null,
        exerciseRecovery: Boolean = false,
    ) = AutonomicBalanceInputs(
        at = NOW,
        lastStressEnd = null,
        rmssdMs = rmssdMs,
        rmssdBaselineMs = RMSSD_BASELINE_MS,
        heartRateBpm = heartRateBpm,
        restingHeartRateBpm = RESTING_BPM,
        sleepScore = null,
        fatiguePercent = null,
        exerciseRecovery = exerciseRecovery,
    )

    @Test
    fun `pulse alone cannot drive the balance to zero`() {
        val balance = calculator.calculate(inputs(heartRateBpm = RESTING_BPM + FULL_EXCESS_BPM))!!

        assertEquals(PERCENT_AT_SINGLE_CAP, balance.percent)
        assertEquals(AutonomicTone.CALM, balance.tone)
    }

    @Test
    fun `both components at full strain still reach zero`() {
        val balance = calculator.calculate(inputs(rmssdMs = VAGAL_OFF_RMSSD_MS, heartRateBpm = RESTING_BPM + FULL_EXCESS_BPM))

        assertEquals(0, balance!!.percent)
        assertEquals(AutonomicTone.STRAINED, balance.tone)
    }

    @Test
    fun `strain during exercise recovery reads as recovering, not strained`() {
        val balance = calculator.calculate(
            inputs(rmssdMs = VAGAL_OFF_RMSSD_MS, heartRateBpm = RESTING_BPM + FULL_EXCESS_BPM, exerciseRecovery = true),
        )

        assertEquals(AutonomicTone.RECOVERING, balance!!.tone)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-08T12:00:00Z")
        const val RESTING_BPM = 60.0
        const val FULL_EXCESS_BPM = 20.0
        const val RMSSD_BASELINE_MS = 50.0
        const val VAGAL_OFF_RMSSD_MS = 20.0
        const val PERCENT_AT_SINGLE_CAP = 40
    }
}
