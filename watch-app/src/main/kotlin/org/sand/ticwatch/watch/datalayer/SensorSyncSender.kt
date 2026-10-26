package org.sand.ticwatch.watch.datalayer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.watch.buffer.BufferedSample
import org.sand.ticwatch.watch.buffer.SensorSampleBuffer
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.WindowScheduler
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pushes sensor samples up to the phone; phone-app fans them out to Health Connect and
 * the backend. Every sample is persisted to [pendingSamples] as soon as it's collected
 * and only removed once a send is actually acknowledged - so a lost connection (watch
 * off the wrist, phone out of Bluetooth range) doesn't drop readings, it just delays
 * them until a later flush delivers the backlog in batches.
 *
 * Flushes are rare on purpose: classic Bluetooth opens a fresh RFCOMM connection per send,
 * which costs radio power and, done per sample, starved other Bluetooth work (e.g. an
 * in-flight APK transfer). A flush runs once [flushThreshold] samples have arrived (the CPU is
 * awake for their delivery anyway) or every [flushInterval] on [scheduler] - an alarm, since a
 * coroutine `delay` never elapses while the watch is suspended. Each flush drains the whole
 * backlog under [awakeRunner], so the device can't suspend between connect and acknowledgement.
 */
class SensorSyncSender(
    private val transport: DeviceLinkTransport,
    private val pendingSamples: SensorSampleBuffer,
    private val scheduler: WindowScheduler,
    private val awakeRunner: AwakeRunner,
    private val flushInterval: Duration,
    private val flushThreshold: Int,
) {
    private val flushLock = Mutex()
    private val enqueuedSinceFlush = AtomicInteger()

    suspend fun sync(samples: Flow<SensorSample>) {
        coroutineScope {
            launch {
                samples.buffer(capacity = 64).collect { sample ->
                    pendingSamples.enqueue(sample)
                    if (enqueuedSinceFlush.incrementAndGet() >= flushThreshold) flushPending()
                }
            }
            while (true) {
                flushPending()
                scheduler.awaitNext(flushInterval)
            }
        }
    }

    private suspend fun flushPending() = flushLock.withLock {
        enqueuedSinceFlush.set(0)
        awakeRunner.runAwake(FLUSH_WAKE_LOCK_TIMEOUT) {
            while (true) {
                val batch = pendingSamples.peek(BATCH_SIZE)
                if (batch.isEmpty()) break
                transport.sendMessage(DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH, toPayload(batch))
                    .onSuccess { pendingSamples.remove(batch.map(BufferedSample::id)) }
                    .onFailure { return@runAwake }
            }
        }
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
        const val BATCH_SIZE = 50

        /** Upper bound for draining a long backlog; the lock is released as soon as the flush ends. */
        val FLUSH_WAKE_LOCK_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}
