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
        fixture.saveCuffReading(135.0, 85.0, cuffAt)
        fixture.clock.advance(Duration.ofMinutes(3))

        val result = fixture.monitor.measureAndSave(HeartRateReading(72.0, fixture.clock.now))

        assertEquals(BloodPressureCalibration(135.0, 85.0, 72.0, null, null, cuffAt), fixture.calibrations.current)
        val estimate = result.measurement as BloodPressureMeasurement.Estimated
        assertEquals(135.0, estimate.reading.systolic, DELTA)
        assertEquals(85.0, estimate.reading.diastolic, DELTA)
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

    @Test
    fun `hypertensive cuff reading keeps a normotensive calibration`() = runTest {
        fixture.calibrations.current = oldCalibration
        fixture.watchHeartRate(96.0, cuffAt)

        assertEquals(CalibrationResult.BaselineKept, fixture.monitor.calibrateFromCuff(149.0, 99.0, cuffAt))
        assertEquals(oldCalibration, fixture.calibrations.current)
    }

    @Test
    fun `hypertensive cuff reading replaces a stale calibration`() = runTest {
        val stale = oldCalibration.copy(calibratedAt = cuffAt.minus(BloodPressureMonitor.CALIBRATION_MAX_AGE).minusSeconds(1))
        fixture.calibrations.current = stale
        fixture.watchHeartRate(70.0, cuffAt.minus(Duration.ofMinutes(1)))

        val result = fixture.monitor.calibrateFromCuff(149.0, 99.0, cuffAt)

        assertEquals(149.0, (result as CalibrationResult.Saved).calibration.systolic, DELTA)
    }

    @Test
    fun `measurement blends a recent pulse-wave estimate by its error`() = runTest {
        fixture.calibrations.current = BloodPressureCalibration(120.0, 80.0, 60.0, null, null, cuffAt.minus(Duration.ofHours(1)))
        fixture.recentPulseWave = PulseWaveBloodPressure(140.0, 90.0, systolicErrorMmHg = PulseWaveBlender.WRIST_MODEL_ERROR_MMHG)

        val estimate = fixture.monitor.measure(HeartRateReading(60.0, cuffAt)) as BloodPressureMeasurement.Estimated

        assertEquals(130.0, estimate.reading.systolic, DELTA)
        assertEquals(85.0, estimate.reading.diastolic, DELTA)
    }

    @Test
    fun `measurement right after an auto-workout cooldown is not at rest`() = runTest {
        fixture.calibrations.current = oldCalibration
        fixture.history.add(workout(SensorType.AUTO_EXERCISE, endedMinutesAgo = 6))

        val result = fixture.monitor.measure(HeartRateReading(60.0, cuffAt))

        assertTrue(result is BloodPressureMeasurement.NotAtRest)
    }

    @Test
    fun `walking 5 to 15 minutes ago is not settled rest`() = runTest {
        fixture.calibrations.current = oldCalibration
        fixture.history.add(SensorSample(SensorType.STEPS, 900.0, cuffAt.minus(Duration.ofMinutes(15)), cuffAt.minus(Duration.ofMinutes(5))))

        val result = fixture.monitor.measure(HeartRateReading(60.0, cuffAt))

        assertTrue(result is BloodPressureMeasurement.NotAtRest)
    }

    @Test
    fun `workout ended over 10 minutes ago allows a measurement`() = runTest {
        fixture.calibrations.current = oldCalibration
        fixture.history.add(workout(SensorType.EXERCISE, endedMinutesAgo = 11))

        val result = fixture.monitor.measure(HeartRateReading(60.0, cuffAt))

        assertTrue(result is BloodPressureMeasurement.Estimated)
    }

    private fun workout(type: SensorType, endedMinutesAgo: Long) =
        SensorSample(type, WORKOUT_ACTIVITY, cuffAt.minus(Duration.ofMinutes(endedMinutesAgo + WORKOUT_MINUTES)), cuffAt.minus(Duration.ofMinutes(endedMinutesAgo)))

    private companion object {
        const val WORKOUT_ACTIVITY = 1.0
        const val WORKOUT_MINUTES = 30L
        const val DELTA = 1e-9
    }
}
