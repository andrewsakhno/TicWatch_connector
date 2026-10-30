package org.sand.ticwatch.phone.health

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class BloodPressureMonitorTest {

    private val cuffAt = Instant.parse("2026-10-29T08:00:00Z")
    private val fixture = BloodPressureTestFixture(cuffAt)
    private val oldCalibration = BloodPressureCalibration(120.0, 80.0, 60.0, null, null, cuffAt.minus(Duration.ofDays(1)))

    @Test
    fun `watch measurement within 5 min of a cuff reading calibrates from it first`() = runTest {
        fixture.calibrations.current = oldCalibration
        fixture.saveCuffReading(140.0, 90.0, cuffAt)
        fixture.clock.advance(Duration.ofMinutes(3))

        val result = fixture.monitor.measureAndSave(HeartRateReading(72.0, fixture.clock.now))

        assertEquals(BloodPressureCalibration(140.0, 90.0, 72.0, null, null, cuffAt), fixture.calibrations.current)
        val estimate = result.measurement as BloodPressureMeasurement.Estimated
        assertEquals(140.0, estimate.reading.systolic, DELTA)
        assertEquals(90.0, estimate.reading.diastolic, DELTA)
        assertTrue(result.saved!!.isSuccess)
    }

    @Test
    fun `watch measurement without a calibration uses a fresh cuff reading`() = runTest {
        fixture.saveCuffReading(130.0, 85.0, cuffAt)
        fixture.clock.advance(Duration.ofMinutes(1))

        val result = fixture.monitor.measureAndSave(HeartRateReading(65.0, fixture.clock.now))

        assertEquals(cuffAt, fixture.calibrations.current?.calibratedAt)
        assertTrue(result.measurement is BloodPressureMeasurement.Estimated)
    }

    @Test
    fun `cuff reading older than 5 min is not applied`() = runTest {
        fixture.calibrations.current = oldCalibration
        fixture.saveCuffReading(140.0, 90.0, cuffAt)
        fixture.clock.advance(Duration.ofMinutes(6))

        val result = fixture.monitor.measureAndSave(HeartRateReading(60.0, fixture.clock.now))

        assertEquals(oldCalibration, fixture.calibrations.current)
        assertEquals(120.0, (result.measurement as BloodPressureMeasurement.Estimated).reading.systolic, DELTA)
    }

    @Test
    fun `cuff reading already behind the calibration is not applied again`() = runTest {
        val current = oldCalibration.copy(calibratedAt = cuffAt)
        fixture.calibrations.current = current
        fixture.saveCuffReading(140.0, 90.0, cuffAt)
        fixture.clock.advance(Duration.ofMinutes(2))

        fixture.monitor.measureAndSave(HeartRateReading(75.0, fixture.clock.now))

        assertEquals(current, fixture.calibrations.current)
    }

    @Test
    fun `calibrateFromCuff pairs the cuff with a stored watch heart rate`() = runTest {
        fixture.watchHeartRate(68.0, cuffAt.plus(Duration.ofMinutes(2)))
        fixture.clock.advance(Duration.ofMinutes(10))

        val result = fixture.monitor.calibrateFromCuff(140.0, 90.0, cuffAt)

        assertEquals(68.0, (result as CalibrationResult.Saved).calibration.heartRate, DELTA)
    }

    @Test
    fun `calibrateFromCuff without a watch heart rate near the cuff reports NoHeartRate`() = runTest {
        fixture.watchHeartRate(68.0, cuffAt.minus(Duration.ofMinutes(20)))

        assertEquals(CalibrationResult.NoHeartRate, fixture.monitor.calibrateFromCuff(140.0, 90.0, cuffAt))
    }

    private companion object {
        const val DELTA = 1e-9
    }
}
