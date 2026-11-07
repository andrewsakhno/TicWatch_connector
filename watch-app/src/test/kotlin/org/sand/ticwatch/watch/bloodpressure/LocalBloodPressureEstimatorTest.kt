package org.sand.ticwatch.watch.bloodpressure

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Same scenarios and expected values as phone-app's `health/BloodPressureEstimatorTest` (keep in sync). */
class LocalBloodPressureEstimatorTest {

    private val estimator = LocalBloodPressureEstimator()
    private val at = Instant.parse("2026-11-07T08:00:00Z")

    @Test
    fun `hypertensive calibration does not floor resting estimates at calibration minus 15`() {
        val calibration = WatchBloodPressureCalibration(149.0, 99.0, 90.7, 50.9, null, at, 0.0)

        val estimate = estimator.estimate(calibration, LocalWristSignals(60.0, 50.9, null), restingHeartRateLow = 55.0)

        assertEquals(122.905, estimate.systolic, DELTA)
        assertEquals(89.79, estimate.diastolic, DELTA)
    }

    @Test
    fun `normotensive calibration keeps the 15 mmHg resting floor`() {
        val calibration = WatchBloodPressureCalibration(125.0, 84.0, 90.0, null, null, at, 0.0)

        val estimate = estimator.estimate(calibration, LocalWristSignals(60.0, null, null), restingHeartRateLow = 55.0)

        assertEquals(110.0, estimate.systolic, DELTA)
    }

    @Test
    fun `falling RMSSD raises diastolic too`() {
        val calibration = WatchBloodPressureCalibration(120.0, 80.0, 60.0, 40.0, null, at, 0.0)

        val estimate = estimator.estimate(calibration, LocalWristSignals(60.0, 20.0, null), restingHeartRateLow = null)

        assertEquals(127.0, estimate.systolic, DELTA)
        assertEquals(82.5, estimate.diastolic, DELTA)
    }

    private companion object {
        const val DELTA = 1e-6
    }
}
