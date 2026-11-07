package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** How long the night monitor listens for snoring, and how often (every [everyEpochs]-th epoch). */
data class NightSnoreCadence(val slot: Duration, val everyEpochs: Int)

/**
 * Night-time breathing monitor: while [sleepState] reports a sleep session and the watch is worn, it
 * listens continuously and reports every [epoch] - SpO2, HRV and breathing rate from [epochAggregators]
 * as intervals spanning the epoch, completed events from [detectors] (oxygen desaturations, pulse-wave amplitude
 * drops when switched on), and the
 * share of snoring, listened to by [snoreMeter] for [NightSnoreCadence.slot] at the start of every
 * [NightSnoreCadence.everyEpochs]-th epoch and stamped over that whole snore period.
 *
 * Unlike the daytime [VendorWindowSampleSource] the sensors stay registered across epochs: re-registering
 * `psp_spo2` restarts its ~2 min convergence, and a desaturation must be followed continuously. Only the
 * per-epoch aggregators are swapped. The sensors are non-wakeup, so when the hub's FIFO holds a
 * [batchCheckInterval] of their events they are batched and the CPU sleeps: an alarm every [batchCheckInterval]
 * flushes the FIFO and re-checks the state, and the epoch closes after a final flush. Otherwise - or as soon as a
 * batched sensor delivers nothing over a worn epoch (it stopped while suspended) - a partial wake lock is held for
 * the rest of the night (renewed every epoch), the former battery cost. An epoch [epochQualifiers]
 * reject (wrist movement reads as desaturation on PPG) drops its body results and its events, not the
 * snoring. When [rest] gives a pause (the "intermittent" setting) each epoch is followed by that long with
 * sensors and wake lock off: far less PPG, at the price of every desaturation that falls into a pause.
 */
class NightBreathingSampleSource(
    private val sensorManager: SensorManager,
    private val powerManager: PowerManager,
    private val scheduler: WindowScheduler,
    private val sleepState: SleepState,
    private val wearState: WearStateSource,
    /** Built at each night's start, like [detectors]. */
    private val epochAggregators: () -> List<() -> VendorReadingAggregator>,
    private val epochQualifiers: List<() -> WindowQualifier>,
    /** Built at each night's start, so a settings change applies from the next night. */
    private val detectors: () -> List<NightEventDetector>,
    private val snoreMeter: SnoreMeter,
    private val epoch: Duration,
    private val snore: NightSnoreCadence,
    private val batchCheckInterval: Duration,
    private val idleCheckInterval: () -> Duration,
    private val rest: () -> Duration?,
) : SampleSource {

    override fun samples(): Flow<SensorSample> = flow {
        while (true) {
            if (shouldMonitor()) {
                monitorNight()
                rest()?.let { scheduler.awaitNext(it) }
            } else {
                scheduler.awaitNext(idleCheckInterval())
            }
        }
    }

    private fun shouldMonitor(): Boolean = sleepState.isAsleep() && wearState.isWorn()

    private suspend fun FlowCollector<SensorSample>.monitorNight() {
        val nightDetectors = detectors()
        val aggregators = epochAggregators()
        val router = EpochRouter(nightDetectors, newEpoch(aggregators))
        // Not reference counted: each timed acquire just extends the one hold.
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        val snoreWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, SNORE_WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        val listeners = mutableListOf<RoutingListener>()
        try {
            val sensors = router.sensorTypes().mapNotNull { type ->
                sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type && !it.isWakeUpSensor }
                    ?: null.also { Log.w(TAG, "Sensor $type not found") }
            }
            val batching = fitsInFifo(sensors, router)
            val latencyUs = if (batching) batchCheckInterval.micros().toInt() else NO_BATCHING_LATENCY_US
            sensors.forEach { sensor ->
                val listener = RoutingListener(router)
                if (sensorManager.registerListener(listener, sensor, router.samplingPeriodUs(sensor.type), latencyUs)) listeners += listener
            }
            Log.i(TAG, "Night monitoring started with ${listeners.size} sensor(s), ${if (batching) "batched (FIFO)" else "under a wake lock"}")
            // Nothing to stay awake for; returning at once would spin the caller's loop.
            if (listeners.isEmpty()) return scheduler.awaitNext(idleCheckInterval())
            var holdAwake = !batching
            var epochIndex = 0
            var pendingSnore: PendingSnore? = null
            var lastEnd = Instant.now()
            while (shouldMonitor()) {
                if (holdAwake) wakeLock.acquire(epoch.plus(WAKE_LOCK_SLACK).toMillis())
                val start = Instant.now()
                val listensForSnoring = epochIndex % snore.everyEpochs == 0
                listeners.forEach { it.resetEventCount() }
                var fraction: Double? = null
                val completed = coroutineScope {
                    val slot = if (listensForSnoring) async { awake(snoreWakeLock, snore.slot) { measureSnoring() } } else null
                    val completed = if (holdAwake) awaitEpochEndAwake() else awaitEpochEndBatched(listeners, wakeLock)
                    if (completed) fraction = slot?.await() else slot?.cancelAndJoin()
                    completed
                }
                // Batched events still in the hub's FIFO belong to this epoch.
                if (batching) drain(listeners, wakeLock)
                val end = Instant.now()
                lastEnd = end
                val closed = router.closeEpoch(newEpoch(aggregators), start, end)
                if (listensForSnoring) pendingSnore = fraction?.let { PendingSnore(it, start) }
                // One listening slot stands for the whole snore period: stamped over it, the phone's minutes add up.
                val snoring = if ((epochIndex + 1) % snore.everyEpochs == 0) pendingSnore.also { pendingSnore = null } else null
                // PPG off the wrist reads as anything: an epoch that ended there reports nothing about the body.
                val worn = wearState.isWorn()
                val samples = (if (worn) closed else emptyList()) + listOfNotNull(snoring?.toSample(end))
                Log.i(TAG, "Night epoch done: ${samples.map { "${it.type}=${"%.2f".format(it.value)}" }}")
                samples.forEach { emit(it) }
                if (!holdAwake && completed && worn && listeners.any { it.eventCount() == 0 }) {
                    // A sensor that stops while the CPU is suspended would silently cost the night: stay awake instead.
                    Log.w(TAG, "A batched sensor delivered nothing over an epoch; holding the wake lock for the rest of the night")
                    holdAwake = true
                }
                epochIndex++
                if (rest() != null) break
            }
            pendingSnore?.let { emit(it.toSample(lastEnd)) }
        } finally {
            listeners.forEach { sensorManager.unregisterListener(it) }
            if (wakeLock.isHeld) wakeLock.release()
            if (snoreWakeLock.isHeld) snoreWakeLock.release()
            Log.i(TAG, "Night monitoring stopped")
        }
    }

    /**
     * Whether the hub's FIFO (shared by all sensors, so the smallest reported capacity counts) holds every event the
     * [sensors] produce in one [batchCheckInterval], with margin. Non-wakeup events beyond it would be overwritten
     * while the CPU sleeps. Unknown rates (fastest, no minimum delay) never fit.
     */
    private fun fitsInFifo(sensors: List<Sensor>, router: EpochRouter): Boolean {
        if (sensors.isEmpty()) return false
        val capacity = sensors.minOf { it.fifoMaxEventCount }
        val periodsUs = sensors.map { effectivePeriodUs(router.samplingPeriodUs(it.type), it) }
        if (periodsUs.any { it == null }) {
            Log.i(TAG, "Night FIFO: a sensor's rate is unknown, not batching")
            return false
        }
        val needed = periodsUs.filterNotNull().sumOf { batchCheckInterval.micros() / it } * FIFO_MARGIN
        Log.i(TAG, "Night FIFO: ${needed.toLong()} event(s) per ${batchCheckInterval.seconds}s, capacity $capacity")
        return capacity > 0 && needed <= capacity.toDouble()
    }

    /** Maps the `SENSOR_DELAY_*` constants to their period; never faster than the sensor's own minimum delay. */
    private fun effectivePeriodUs(requested: Int, sensor: Sensor): Long? {
        val period = DELAY_PERIODS_US[requested]?.takeIf { it > 0 } ?: requested.toLong().takeIf { requested > MAX_DELAY_CONSTANT }
        val floor = sensor.minDelay.toLong().takeIf { it > 0 }
        return listOfNotNull(period, floor).maxOrNull()
    }

    /**
     * Waits out the epoch under the held wake lock, but no longer than [STATE_CHECK_INTERVAL] past waking up or taking
     * the watch off, so the PPG and the wake lock don't outlive the session by up to a whole [epoch]. False if it ended early.
     */
    private suspend fun awaitEpochEndAwake(): Boolean {
        var remaining = epoch
        while (remaining > Duration.ZERO) {
            val step = minOf(remaining, STATE_CHECK_INTERVAL)
            delay(step.toMillis())
            remaining -= step
            if (!shouldMonitor()) return false
        }
        return true
    }

    /**
     * Waits out the epoch with the CPU free to suspend: an alarm every [batchCheckInterval] drains the FIFO (before it
     * overflows) and re-checks sleep and wear. Wall-clock based, since alarms may fire late. False if it ended early.
     */
    private suspend fun awaitEpochEndBatched(listeners: List<RoutingListener>, wakeLock: PowerManager.WakeLock): Boolean {
        val deadline = Instant.now().plus(epoch)
        while (true) {
            val remaining = Duration.between(Instant.now(), deadline)
            if (remaining <= Duration.ZERO) return true
            scheduler.awaitNext(minOf(remaining, batchCheckInterval))
            if (Duration.between(Instant.now(), deadline) > Duration.ZERO) drain(listeners, wakeLock)
            if (!shouldMonitor()) return false
        }
    }

    /** Asks the hub to deliver everything batched so far and waits (awake, bounded) until it has. */
    private suspend fun drain(listeners: List<RoutingListener>, wakeLock: PowerManager.WakeLock) =
        awake(wakeLock, FLUSH_TIMEOUT) {
            val requested = listeners.filter { it.requestFlush(sensorManager) }
            withTimeoutOrNull(FLUSH_TIMEOUT.toMillis()) { requested.forEach { it.awaitFlushed() } }
                ?: Log.w(TAG, "Sensor FIFO flush not confirmed within $FLUSH_TIMEOUT")
        }

    private suspend fun <T> awake(lock: PowerManager.WakeLock, duration: Duration, block: suspend () -> T): T {
        lock.acquire(duration.plus(WAKE_LOCK_SLACK).toMillis())
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    /** A failing microphone costs this slot's snoring, not the night's monitoring (or the whole sample flow). */
    private suspend fun measureSnoring(): Double? = try {
        snoreMeter.snoringFraction(snore.slot)
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        Log.w(TAG, "Snoring not measured", error)
        null
    }

    /** A measured snoring fraction waiting for the end of its snore period. */
    private class PendingSnore(val fraction: Double, val start: Instant) {
        fun toSample(end: Instant) = SensorSample(SensorType.SNORING, fraction, start, end)
    }

    private fun newEpoch(aggregators: List<() -> VendorReadingAggregator>) = Epoch(aggregators.map { it() }, epochQualifiers.map { it() })

    private class Epoch(val aggregators: List<VendorReadingAggregator>, val qualifiers: List<WindowQualifier>) {
        val listeners: List<WindowSensorListener> get() = aggregators + qualifiers
    }

    /**
     * Hands sensor events (main thread) to the night-long detectors and the current epoch's listeners,
     * and swaps the epoch (collector coroutine) under the same lock, so no event lands in a closed epoch.
     */
    private class EpochRouter(private val detectors: List<NightEventDetector>, private var current: Epoch) {
        private val lock = Any()

        fun sensorTypes(): Set<Int> = (detectors + current.listeners).map { it.sensorType }.toSet()

        /** The fastest rate any listener of [type] asks for (a smaller period is faster). */
        fun samplingPeriodUs(type: Int): Int = (detectors + current.listeners).filter { it.sensorType == type }.minOf { it.samplingPeriodUs }

        fun dispatch(type: Int, values: FloatArray, at: Instant) = synchronized(lock) {
            detectors.forEach { if (it.sensorType == type) it.onEvent(values, at) }
            current.listeners.forEach { if (it.sensorType == type) it.onEvent(values, at) }
        }

        /** Results of the ending epoch stamped over [start, end], plus completed events; then [next] takes over. */
        fun closeEpoch(next: Epoch, start: Instant, end: Instant): List<SensorSample> = synchronized(lock) {
            val ending = current
            current = next
            val events = detectors.flatMap { it.drain() }
            if (!ending.qualifiers.all { it.accepts() }) return@synchronized emptyList()
            ending.aggregators.mapNotNull { it.result(start, end)?.copy(recordedAt = start, endedAt = end) } + events
        }
    }

    /** Routes one sensor's events, counts them per epoch and signals completed FIFO flushes. */
    private class RoutingListener(private val router: EpochRouter) : SensorEventListener2 {
        private val events = AtomicInteger()
        private val flushed = Channel<Unit>(Channel.CONFLATED)

        override fun onSensorChanged(event: SensorEvent) {
            events.incrementAndGet()
            router.dispatch(event.sensor.type, event.values, event.wallClockTime())
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        override fun onFlushCompleted(sensor: Sensor) {
            flushed.trySend(Unit)
        }

        fun resetEventCount() = events.set(0)

        fun eventCount(): Int = events.get()

        /** False when the hub refused the flush (nothing to wait for). */
        fun requestFlush(sensorManager: SensorManager): Boolean {
            flushed.tryReceive()
            return sensorManager.flush(this)
        }

        suspend fun awaitFlushed() = flushed.receive()
    }

    private companion object {
        const val TAG = "NightBreathingSource"
        const val WAKE_LOCK_TAG = "TicWatchConnector:NightBreathing"
        const val SNORE_WAKE_LOCK_TAG = "TicWatchConnector:NightSnore"
        val WAKE_LOCK_SLACK: Duration = Duration.ofSeconds(30)

        /** Held awake, the CPU runs for the sensors anyway, so re-reading the (in-memory) states costs nothing. */
        val STATE_CHECK_INTERVAL: Duration = Duration.ofSeconds(15)

        /** Delivery as it happens: the wake-lock fallback. */
        const val NO_BATCHING_LATENCY_US = 0

        /** Head room over the estimated event count, since vendor rates are approximate. */
        const val FIFO_MARGIN = 1.5
        const val NANOS_PER_MICRO = 1_000L

        fun Duration.micros(): Long = toNanos() / NANOS_PER_MICRO

        val FLUSH_TIMEOUT: Duration = Duration.ofSeconds(3)

        /** `SensorManager.SENSOR_DELAY_*` constants; any larger value is a period in microseconds. */
        const val MAX_DELAY_CONSTANT = SensorManager.SENSOR_DELAY_NORMAL
        val DELAY_PERIODS_US: Map<Int, Long> = mapOf(
            SensorManager.SENSOR_DELAY_FASTEST to 0L,
            SensorManager.SENSOR_DELAY_GAME to 20_000L,
            SensorManager.SENSOR_DELAY_UI to 66_667L,
            SensorManager.SENSOR_DELAY_NORMAL to 200_000L,
        )
    }
}
