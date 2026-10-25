package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class SensorSample(val type: SensorType, val value: Double, val recordedAt: Instant)

enum class SensorType { HEART_RATE, STEPS, CALORIES }

/**
 * Streams on-watch sensor readings. Abstracted (DIP/ISP) so
 * [org.sand.ticwatch.watch.datalayer.SensorSyncSender] doesn't depend on the Health
 * Services SDK directly, keeping that dependency swappable/testable.
 */
interface SensorCollector {
    fun heartRateSamples(): Flow<SensorSample>
    fun stepSamples(): Flow<SensorSample>
}
