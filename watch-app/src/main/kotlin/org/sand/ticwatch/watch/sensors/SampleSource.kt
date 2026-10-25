package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.Flow

/**
 * A stream of samples produced in the background - Health Services passive data, or
 * periodic vendor-sensor measurement windows. Kept separate from [SensorCollector]
 * (ISP): these aren't live MeasureClient streams, and the service just merges every
 * source into the sync pipeline.
 */
interface SampleSource {
    fun samples(): Flow<SensorSample>
}
