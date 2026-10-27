package org.sand.ticwatch.phone.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

class ExerciseLoadCalculatorTest {

    private val calculator = ExerciseLoadCalculator()
    private val start = Instant.parse("2026-10-27T08:00:00Z")

    @Test
    fun `steady effort lands in one zone with Banister TRIMP`() {
        val end = start.plus(Duration.ofMinutes(10))
        val readings = (0L until 60L).map { heartRate(150.0, start.plusSeconds(it * 10)) }

        val load = calculator.load(readings, end, RESTING, MAX, TrimpCoefficient.MALE)

        // Reserve fraction (150 - 60) / (180 - 60) = 0.75: zone 3.
        assertEquals(mapOf(HeartRateZone.ZONE_3 to Duration.ofMinutes(10)), load.zoneDurations)
        assertEquals(10 * 0.75 * 0.64 * exp(1.92 * 0.75), load.trimp, DELTA)
    }

    @Test
    fun `a reading counts at most the reading span across a gap`() {
        val readings = listOf(heartRate(175.0, start), heartRate(175.0, start.plus(Duration.ofMinutes(5))))

        val load = calculator.load(readings, start.plus(Duration.ofMinutes(10)), RESTING, MAX, TrimpCoefficient.MALE)

        assertEquals(mapOf(HeartRateZone.ZONE_5 to Duration.ofMinutes(1)), load.zoneDurations)
    }

    @Test
    fun `easy heart rate is below every zone and adds little load`() {
        val readings = listOf(heartRate(70.0, start))

        val load = calculator.load(readings, start.plusSeconds(30), RESTING, MAX, TrimpCoefficient.FEMALE)

        assertEquals(emptyMap<HeartRateZone, Duration>(), load.zoneDurations)
        assertEquals(0.5 * (10.0 / 120) * 0.64 * exp(1.67 * 10.0 / 120), load.trimp, DELTA)
    }

    private fun heartRate(bpm: Double, at: Instant) = SensorSample(SensorType.HEART_RATE, bpm, at)

    private companion object {
        const val RESTING = 60.0
        const val MAX = 180.0
        const val DELTA = 1e-9
    }
}
