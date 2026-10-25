package org.sand.ticwatch.phone.health

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class CorrelatedBodyTemperatureEstimatorTest {

    private val zone = ZoneOffset.UTC

    // 11:00 is six hours before the circadian peak, where the circadian offset is zero.
    private val windowEnd = Instant.parse("2026-10-20T11:00:00Z")
    private val windowStart = windowEnd.minus(MEASUREMENT_WINDOW)

    private val noRestingMinimums = object : DailyMinimumSource {
        override fun get(day: LocalDate): Double? = null
    }
    private val noNightlyBaseline = SkinBaselineSource { null }

    private val estimator: SampleDeriver = run {
        val restingBaseline = RestingHeartRateBaseline(noRestingMinimums, zone)
        CorrelatedBodyTemperatureEstimator(
            models = listOf(
                DualHeatFluxCoreModel(restingBaseline, CircadianOffset(zone)),
                QualifiedCoreModel(listOf(HeartRateElevationQualifier(restingBaseline)), SkinOffsetCoreModel(noNightlyBaseline)),
            ),
            qualifiers = listOf(ExerciseRecoveryQualifier(), StepCadenceQualifier()),
        )
    }

    @Test
    fun `cold ambient and cool skin give a normal core`() {
        val core = estimate(skin(31.0), ambient(10.0), *heartRate(RESTING_BPM), restingHeartRate())

        assertEquals(1, core.size)
        assertTrue("core ${core.single().value}", core.single().value in 36.6..36.8)
    }

    @Test
    fun `raised resting heart rate reads as fever`() {
        val normal = estimate(skin(33.0), ambient(22.0), *heartRate(RESTING_BPM), restingHeartRate()).single().value
        val fever = estimate(skin(33.0), ambient(22.0), *heartRate(FEVER_BPM), restingHeartRate()).single().value

        assertTrue("fever core $fever", fever in 37.5..40.0)
        assertTrue("fever $fever vs normal $normal", fever - normal >= 1.5)
    }

    @Test
    fun `walking drops the reading`() {
        val walking = SensorSample(SensorType.STEPS, WALKING_STEPS, windowEnd.minus(Duration.ofMinutes(10)), windowEnd)

        val core = estimate(skin(31.0), ambient(10.0), *heartRate(WALKING_BPM), restingHeartRate(), walking)

        assertTrue(core.isEmpty())
    }

    @Test
    fun `exercise session drops the reading`() {
        val exercise = SensorSample(SensorType.EXERCISE, EXERCISE_TYPE_ID, windowEnd.minus(Duration.ofMinutes(40)), windowEnd.minus(Duration.ofMinutes(20)))

        val core = estimate(skin(31.0), ambient(10.0), *heartRate(RESTING_BPM), restingHeartRate(), exercise)

        assertTrue(core.isEmpty())
    }

    @Test
    fun `missing ambient falls back to the skin-only model`() {
        val core = estimate(skin(33.5), *heartRate(RESTING_BPM), restingHeartRate())

        assertEquals(36.8, core.single().value, TOLERANCE)
    }

    @Test
    fun `fallback still rejects a raised heart rate`() {
        val core = estimate(skin(33.5), *heartRate(FEVER_BPM), restingHeartRate())

        assertTrue(core.isEmpty())
    }

    @Test
    fun `ambient at skin temperature falls back to the skin-only model`() {
        val core = estimate(skin(33.5), ambient(34.0), *heartRate(RESTING_BPM), restingHeartRate())

        assertEquals(36.8, core.single().value, TOLERANCE)
    }

    @Test
    fun `off-wrist skin reading is skipped`() {
        assertTrue(estimate(skin(25.0), ambient(20.0)).isEmpty())
    }

    private fun estimate(vararg samples: SensorSample): List<SensorSample> =
        runBlocking { estimator.derive(samples.toList()) }

    private fun skin(celsius: Double) = SensorSample(SensorType.SKIN_TEMPERATURE, celsius, windowStart, windowEnd)

    private fun ambient(celsius: Double) = SensorSample(SensorType.AMBIENT_TEMPERATURE, celsius, windowStart, windowEnd)

    private fun restingHeartRate() = SensorSample(SensorType.RESTING_HEART_RATE, RESTING_BPM, windowEnd.minus(Duration.ofHours(3)))

    /** One reading a second over the window and the heart-rate lookback before it. */
    private fun heartRate(bpm: Double): Array<SensorSample> {
        val from = windowStart.minus(HEART_RATE_LOOKBACK)
        return (0 until Duration.between(from, windowEnd).seconds.toInt())
            .map { SensorSample(SensorType.HEART_RATE, bpm, from.plusSeconds(it.toLong())) }
            .toTypedArray()
    }

    private companion object {
        val MEASUREMENT_WINDOW: Duration = Duration.ofMinutes(3)
        val HEART_RATE_LOOKBACK: Duration = Duration.ofMinutes(5)
        const val RESTING_BPM = 60.0
        const val FEVER_BPM = 88.0
        const val WALKING_BPM = 105.0
        const val WALKING_STEPS = 1_100.0
        const val EXERCISE_TYPE_ID = 79.0
        const val TOLERANCE = 0.01
    }
}
