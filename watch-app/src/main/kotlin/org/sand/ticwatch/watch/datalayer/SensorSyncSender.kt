package org.sand.ticwatch.watch.datalayer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.sand.ticwatch.watch.buffer.BufferedSample
import org.sand.ticwatch.watch.buffer.SensorSampleBuffer
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.WindowScheduler
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.LinkHold
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
 * Flushes are rare on purpose: connecting classic Bluetooth costs radio power and, done per
 * sample, starved other Bluetooth work (e.g. an in-flight APK transfer); one flush uses one
 * [liveLink] socket however many batches it sends. A flush runs once [flushThreshold] samples have arrived (the CPU is
 * awake for their delivery anyway) or every [flushInterval] on [scheduler] - an alarm, since a
 * coroutine `delay` never elapses while the watch is suspended. Each flush drains the whole
 * backlog under [awakeRunner], so the device can't suspend between connect and acknowledgement.
 * [companions] (small status messages, e.g. the battery level) go out at the end of every flush.
 *
 * While [liveSync] is active (the phone app is on screen) it flushes every [LIVE_FLUSH_INTERVAL] instead, on a coroutine
 * `delay` under a wake lock: an inexact alarm can't pace seconds. Companions then go out only every
 * [LIVE_FLUSHES_PER_COMPANION_RUN] flushes, and an idle link stays silent, since an empty buffer sends nothing.
 * [liveLink] keeps one socket open for all of it; it is closed by the first pass of the loop after live sync ends.
 */
class SensorSyncSender(
    private val transport: DeviceLinkTransport,
    private val pendingSamples: SensorSampleBuffer,
    private val scheduler: WindowScheduler,
    private val awakeRunner: AwakeRunner,
    private val flushInterval: Duration,
    private val flushThreshold: Int,
    private val companions: List<FlushCompanion> = emptyList(),
    private val liveSync: LiveSyncSignal,
    private val liveLink: LinkHold,
) {
    private val flushLock = Mutex()
    private val enqueuedSinceFlush = AtomicInteger()
    private var liveFlushes = 0

    suspend fun sync(samples: Flow<SensorSample>) {
        coroutineScope {
            launch {
                samples.buffer(capacity = 64).collect { sample ->
                    pendingSamples.enqueue(sample)
                    if (enqueuedSinceFlush.incrementAndGet() >= flushThreshold) flushPending()
                }
            }
            while (true) {
                if (liveSync.isActive()) {
                    liveLink.hold()
                    flushLive()
                } else {
                    liveLink.release()
                    flushPending()
                }
                awaitNextFlush()
            }
        }
    }

    private suspend fun flushLive() {
        val withCompanions = liveFlushes++ % LIVE_FLUSHES_PER_COMPANION_RUN == 0
        flushPending(withCompanions)
    }

    private suspend fun awaitNextFlush() {
        if (liveSync.isActive()) {
            awakeRunner.runAwake(LIVE_WAIT_WAKE_LOCK_TIMEOUT) { delay(LIVE_FLUSH_INTERVAL.toMillis()) }
        } else {
            liveFlushes = 0
            merge(flow { scheduler.awaitNext(flushInterval); emit(Unit) }, liveSync.active.filter { it }.map { }).first()
        }
    }

    /**
     * Delivers the whole backlog now, e.g. before the phone computes something from the latest samples.
     * Returns false when a batch could not be sent (the rest stays buffered for the next flush).
     */
    suspend fun flushNow(): Boolean = flushPending()

    private suspend fun flushPending(withCompanions: Boolean = true): Boolean = flushLock.withLock {
        enqueuedSinceFlush.set(0)
        awakeRunner.runAwake(FLUSH_WAKE_LOCK_TIMEOUT) {
            // One socket for all batches and companions; it only connects if something is actually sent.
            liveLink.hold()
            try {
                while (true) {
                    val batch = pendingSamples.peek(BATCH_SIZE)
                    if (batch.isEmpty()) break
                    transport.sendMessage(DataLayerPaths.MESSAGE_SENSOR_DATA_PUSH, toPayload(batch))
                        .onSuccess { pendingSamples.remove(batch.map(BufferedSample::id)) }
                        .onFailure { return@runAwake false }
                }
                if (withCompanions) companions.forEach { it.onFlush() }
                true
            } finally {
                if (!liveSync.isActive()) liveLink.release()
            }
        }
    }

    private fun toPayload(batch: List<BufferedSample>): ByteArray {
        val entries = JSONArray()
        batch.forEach { buffered -> entries.put(SensorSampleJson.encode(buffered.sample)) }
        return entries.toString().toByteArray(Charsets.UTF_8)
    }

    private companion object {
        /** ~25 KB of JSON: well under the resumable-frame threshold, and each batch makes the phone re-derive and reply. */
        const val BATCH_SIZE = 200

        /** Upper bound for draining a long backlog; the lock is released as soon as the flush ends. */
        val FLUSH_WAKE_LOCK_TIMEOUT: Duration = Duration.ofMinutes(2)

        val LIVE_FLUSH_INTERVAL: Duration = Duration.ofSeconds(5)
        val LIVE_WAIT_WAKE_LOCK_TIMEOUT: Duration = Duration.ofSeconds(30)

        /** With [LIVE_FLUSH_INTERVAL]: the battery level once a minute while live. */
        const val LIVE_FLUSHES_PER_COMPANION_RUN = 12
    }
}

/** Status that piggybacks on [SensorSyncSender]'s flushes instead of waking the radio on its own. */
fun interface FlushCompanion {
    suspend fun onFlush()
}
