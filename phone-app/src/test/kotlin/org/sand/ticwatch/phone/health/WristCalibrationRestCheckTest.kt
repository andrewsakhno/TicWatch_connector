package org.sand.ticwatch.phone.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class WristCalibrationRestCheckTest {

    private val at = Instant.parse("2026-11-07T08:00:00Z")
    private val history = InMemoryHistory()
    private val signals = WristSignalsReader(SampleWindowReader(history))

    private fun check(ceiling: Double?) = WristCalibrationRestCheck(signals) { ceiling }

    @Test
    fun `without a resting heart rate the awake corridor top limits the pulse`() = runTest {
        assertEquals(CalibrationRestViolation.AboveRestCeiling(96.0, 80.0), check(ceiling = 80.0).violation(96.0, at))
    }

    @Test
    fun `without a resting heart rate or corridor the physiological ceiling applies`() = runTest {
        assertEquals(
            CalibrationRestViolation.AboveRestCeiling(96.0, CalibrationRestLimits.FALLBACK_REST_CEILING_BPM),
            check(ceiling = null).violation(96.0, at),
        )
        assertNull(check(ceiling = null).violation(80.0, at))
    }

    @Test
    fun `a known resting heart rate takes precedence over the ceiling`() = runTest {
        history.add(SensorSample(SensorType.RESTING_HEART_RATE, 70.0, at.minus(Duration.ofHours(1))))

        assertNull(check(ceiling = 75.0).violation(80.0, at))
        assertEquals(CalibrationRestViolation.AboveResting(96.0, 70.0), check(ceiling = 75.0).violation(96.0, at))
    }
}
