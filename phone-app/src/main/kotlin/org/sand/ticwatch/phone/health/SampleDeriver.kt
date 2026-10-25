package org.sand.ticwatch.phone.health

/**
 * Derives phone-side metrics the watch has no data type for from a push's samples
 * (Strategy, OCP: a new derived metric is a new implementation, registered in the
 * composition root). Derivers run in order and each sees the samples derived before it.
 */
interface SampleDeriver {
    suspend fun derive(samples: List<SensorSample>): List<SensorSample>
}
