package org.sand.ticwatch.phone.ecg

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RidgePulseBloodPressureEstimatorTest {

    private class FakePairs(private val rows: List<PulseBloodPressurePairEntity>) : PulseBloodPressurePairDao {
        override suspend fun insert(pair: PulseBloodPressurePairEntity): Long = -1

        override suspend fun newest(limit: Int): List<PulseBloodPressurePairEntity> = rows.sortedByDescending { it.cuffAtMs }.take(limit)
    }

    /** Wider, weaker pulses at higher pressure, as in the device pairs. */
    private fun pair(index: Int, systolic: Double, diastolic: Double, halfWidthMs: Double, perfusionIndex: Double) =
        PulseBloodPressurePairEntity(
            cuffAtMs = index * HOUR_MS, systolic = systolic, diastolic = diastolic, recordedAtMs = index * HOUR_MS,
            heartRate = 90.0 + index % 2, riseTimeMs = 120.0 + index * 7 % 11, halfWidthMs = halfWidthMs, perfusionIndex = perfusionIndex,
        )

    private val pairs = listOf(
        pair(1, 125.0, 84.0, 323.0, 0.64),
        pair(2, 129.0, 85.0, 340.0, 0.58),
        pair(3, 138.0, 91.0, 370.0, 0.47),
        pair(4, 149.0, 99.0, 400.0, 0.38),
    )

    @Test
    fun `three pairs still need calibration`() = runTest {
        assertEquals(PulseBloodPressure.NeedsCalibration(3, 4), RidgePulseBloodPressureEstimator(FakePairs(pairs.take(3))).estimate(features(360.0, 0.5)))
    }

    @Test
    fun `four pairs estimate and follow pulse morphology`() = runTest {
        val estimator = RidgePulseBloodPressureEstimator(FakePairs(pairs))

        val narrow = estimator.estimate(features(330.0, 0.62)) as PulseBloodPressure.Estimate
        val wide = estimator.estimate(features(395.0, 0.40)) as PulseBloodPressure.Estimate

        assertEquals(4, wide.pairs)
        assertTrue(wide.systolic > narrow.systolic)
        assertTrue(wide.diastolic > narrow.diastolic)
    }

    private fun features(halfWidthMs: Double, perfusionIndex: Double) = PulseFeatures(90.5, 125.0, halfWidthMs, perfusionIndex)

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
