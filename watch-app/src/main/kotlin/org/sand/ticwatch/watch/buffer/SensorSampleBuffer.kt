package org.sand.ticwatch.watch.buffer

import org.sand.ticwatch.watch.sensors.SensorSample

data class BufferedSample(val id: Long, val sample: SensorSample)

/**
 * Durable queue of sensor samples awaiting delivery to the phone. A sample is persisted
 * here as soon as it's collected and only removed once a transport send for it is
 * actually acknowledged, so readings survive a lost connection (watch off the wrist,
 * phone out of Bluetooth range) or the app process dying, instead of being dropped at
 * collection time.
 *
 * One implementation backs every [org.sand.ticwatch.watch.sensors.SensorType] via a
 * `type` column rather than a table per type, so adding a new sensor type needs no
 * schema change (OCP).
 */
interface SensorSampleBuffer {
    suspend fun enqueue(sample: SensorSample)
    suspend fun peek(limit: Int): List<BufferedSample>
    suspend fun remove(ids: List<Long>)
}
