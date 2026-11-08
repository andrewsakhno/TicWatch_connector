package org.sand.ticwatch.phone.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Same scenarios and expected values as watch-app's `bloodpressure/LocalBloodPressureEstimatorTest` (keep in sync). */
class BloodPressureEstimatorTest {

    private val estimator = BloodPressureEstimator()
    private val at = Instant.parse("2026-11-07T08:00:00Z")

    private fun signals(heartRate: Double, rmssdMs: Double?) = WristSignals(heartRate, at, rmssdMs, null)

    @Test
    fun `hypertensive calibration does not floor resting estimates at calibration minus 15`() {
        val calibration = BloodPressureCalibration(149.0, 99.0, 90.7, 50.9, null, at)

        val estimate = estimator.estimate(calibration, signals(60.0, 50.9), at, restingHeartRateLow = 55.0)

        assertEquals(122.905, estimate.systolic, DELTA)
        assertEquals(89.79, estimate.diastolic, DELTA)
    }

    @Test
    fun `normotensive calibration keeps the 15 mmHg resting floor`() {
        val calibration = BloodPressureCalibration(125.0, 84.0, 90.0, null, null, at)

        val estimate = estimator.estimate(calibration, signals(60.0, null), at, restingHeartRateLow = 55.0)

        assertEquals(110.0, estimate.systolic, DELTA)
    }

    @Test
    fun `falling RMSSD raises diastolic too`() {
        val calibration = BloodPressureCalibration(120.0, 80.0, 60.0, 40.0, null, at)

        val estimate = estimator.estimate(calibration, signals(60.0, 20.0), at)

        assertEquals(127.0, estimate.systolic, DELTA)
        assertEquals(82.5, estimate.diastolic, DELTA)
    }

    @Test
    fun `skin cooling into cold air after an outdoor walk is ignored`() {
        val calibration = BloodPressureCalibration(120.0, 80.0, 60.0, null, SKIN_BASELINE, at)

        val estimate = estimator.estimate(calibration, WristSignals(60.0, at, null, 24.7, 24.7), at)

        assertEquals(120.0, estimate.systolic, DELTA)
        assertEquals(80.0, estimate.diastolic, DELTA)
    }

    @Test
    fun `skin warming saturates at the effective delta`() {
        val calibration = BloodPressureCalibration(120.0, 80.0, 60.0, null, SKIN_BASELINE, at)

        val estimate = estimator.estimate(calibration, WristSignals(60.0, at, null, 38.2, null), at)

        assertEquals(117.10791725977255, estimate.systolic, DELTA)
        assertEquals(76.38489657471568, estimate.diastolic, DELTA)
    }

    @Test
    fun `cold skin attenuates the cooling shift`() {
        val calibration = BloodPressureCalibration(120.0, 80.0, 60.0, null, SKIN_BASELINE, at)

        val estimate = estimator.estimate(calibration, WristSignals(60.0, at, null, 28.5, null), at)

        assertEquals(122.22714826384025, estimate.systolic, DELTA)
        assertEquals(82.78393532980031, estimate.diastolic, DELTA)
    }

    @Test
    fun `warm skin and air keep the saturated cooling shift`() {
        val calibration = BloodPressureCalibration(120.0, 80.0, 60.0, null, SKIN_BASELINE, at)

        val estimate = estimator.estimate(calibration, WristSignals(60.0, at, null, 30.0, 30.0), at)

        assertEquals(122.56945474641749, estimate.systolic, DELTA)
        assertEquals(83.21181843302186, estimate.diastolic, DELTA)
    }

    private companion object {
        const val DELTA = 1e-6
        const val SKIN_BASELINE = 33.2
    }
}
