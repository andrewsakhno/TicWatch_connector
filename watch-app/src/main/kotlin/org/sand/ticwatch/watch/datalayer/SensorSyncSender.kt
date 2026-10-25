package org.sand.ticwatch.watch.datalayer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.watch.buffer.BufferedSample
import org.sand.ticwatch.watch.buffer.SensorSampleBuffer
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/**
 * Pushes sensor samples up to the phone; phone-app fans them out to Health Connect and
 * the backend. Every sample is persisted to [pendingSamples] as soon as it's collected
 * and only removed once a send is actually acknowledged - so a lost connection (watch
 * off the wrist, phone out of Bluetooth range) doesn't drop readings, it just delays
 * them until the periodic retry delivers the backlog in a batch.
 *
 * Flushing only happens on the periodic tick, never immediately per collected sample:
 * classic Bluetooth opens a fresh RFCOMM connection per send, and flushing on every
 * sample turned that into roughly one connect/disconnect cycle per second even while
 * continuously connected - enough churn to start starving other Bluetooth work (e.g.
 * an in-flight APK transfer) sharing the same radio.
 */
class SensorSyncSender(
    private val transport: DeviceLinkTransport,
    private val pendingSamples: SensorSampleBuffer,
) {
    suspend fun sync(samples: Flow<SensorSample>) {
        coroutineScope {
            launch {
                samples.buffer(capacity = 64).collect { sample -> pendingSamples.enqueue(sample) }
            }
            while (true) {
                delay(RETRY_INTERVAL_MS)
                flushPending()
            }
        }
    }

    private suspend fun flushPending() {
        val batch = pendingSamples.peek(BATCH_SIZE)
        if (batch.isEmpty()) return

        transport.sendMessage(DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH, toPayload(batch))
            .onSuccess { pendingSamples.remove(batch.map(BufferedSample::id)) }
    }

    private fun toPayload(batch: List<BufferedSample>): ByteArray {
        val entries = JSONArray()
        batch.forEach { buffered ->
            val entry = JSONObject()
                .put("type", buffered.sample.type.name)
                .put("value", buffered.sample.value)
                .put("recordedAtEpochMs", buffered.sample.recordedAt.toEpochMilli())
            buffered.sample.endedAt?.let { entry.put("endedAtEpochMs", it.toEpochMilli()) }
            entries.put(entry)
        }
        return entries.toString().toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val RETRY_INTERVAL_MS = 5_000L
        const val BATCH_SIZE = 50
    }
}
