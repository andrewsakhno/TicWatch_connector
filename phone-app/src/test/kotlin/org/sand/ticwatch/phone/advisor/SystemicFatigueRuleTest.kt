package org.sand.ticwatch.phone.advisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SystemicFatigueRuleTest {

    private val rule = SystemicFatigueRule()

    @Test
    fun `no index gives no factor`() {
        assertNull(rule.evaluate(inputs(fatiguePercent = null)))
    }

    @Test
    fun `mild fatigue is informational`() {
        val factor = rule.evaluate(inputs(fatiguePercent = 25))!!
        assertEquals(ReadinessFactorKind.FATIGUE_INDEX, factor.kind)
        assertEquals(0, factor.points)
        assertFalse(factor.isLimiting)
    }

    @Test
    fun `pronounced fatigue costs 15 points`() {
        assertEquals(-15, rule.evaluate(inputs(fatiguePercent = 65))!!.points)
    }

    @Test
    fun `exhaustion costs 30 points`() {
        assertEquals(-30, rule.evaluate(inputs(fatiguePercent = 85))!!.points)
    }

    private fun inputs(fatiguePercent: Int?) = CardioReadinessInputs(
        at = Instant.parse("2026-10-31T12:00:00Z"),
        heartRateBpm = null,
        heartRateNormBpm = null,
        diastolic = null,
        systolic = null,
        pressureMeasuredAt = null,
        rmssdMs = null,
        dippingPercent = null,
        sleepScore = null,
        steps24h = null,
        trainingLoad48h = null,
        lastIntakeAt = null,
        kp = null,
        fatiguePercent = fatiguePercent,
    )
}
