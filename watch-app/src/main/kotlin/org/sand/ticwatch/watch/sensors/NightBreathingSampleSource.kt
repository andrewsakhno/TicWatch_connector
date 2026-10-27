package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant

/**
 * Night-time breathing monitor: while [sleepState] reports a sleep session and the watch is worn, it
 * listens continuously and reports every [epoch] - SpO2, HRV and breathing rate from [epochAggregators]
 * as intervals spanning the epoch, completed events from [detectors] (oxygen desaturations), and the
 * share of the epoch spent snoring, measured over its first [snoreSlot] by [snoreMeter].
 *
 * Unlike the daytime [VendorWindowSampleSource] the sensors stay registered across epochs: re-registering
 * `psp_spo2` restarts its ~2 min convergence, and a desaturation must be followed continuously. Only the
 * per-epoch aggregators are swapped. A partial wake lock is held for the whole night (renewed every epoch)
 * because these sensors are non-wakeup - this is the feature's battery cost. An epoch [epochQualifiers]
 * reject (wrist movement reads as desaturation on PPG) drops its body results and its events, not the
 * snoring.
 */
class NightBreathingSampleSource(
    private val sensorManager: SensorManager,
    private val powerManager: PowerManager,
    private val scheduler: WindowScheduler,
    private val sleepState: SleepState,
    private val wearState: WearStateSource,
    private val epochAggregators: List<() -> VendorReadingAggregator>,
    private val epochQualifiers: List<() -> WindowQualifier>,
    private val detectors: List<() -> NightEventDetector>,
    private val snoreMeter: SnoreMeter,
    private val epoch: Duration,
    private val snoreSlot: Duration,
    private val idleCheckInterval: Duration,
) : SampleSource {

    override fun samples(): Flow<SensorSample> = flow {
        while (true) {
            if (shouldMonitor()) monitorNight() else scheduler.awaitNext(idleCheckInterval)
        }
    }

    private fun shouldMonitor(): Boolean = sleepState.isAsleep() && wearState.isWorn()

    private suspend fun FlowCollector<SensorSample>.monitorNight() {
        val nightDetectors = detectors.map { it() }
        val router = EpochRouter(nightDetectors, newEpoch())
        val listeners = router.sensorTypes().mapNotNull { type ->
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type && !it.isWakeUpSensor }
                ?: return@mapNotNull null.also { Log.w(TAG, "Sensor $type not found") }
            RoutingListener(router).takeIf { sensorManager.registerListener(it, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        // Not reference counted: each epoch's timed acquire just extends the one hold.
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
        Log.i(TAG, "Night monitoring started with ${listeners.size} sensor(s)")
        try {
            while (shouldMonitor()) {
                wakeLock.acquire(epoch.plus(WAKE_LOCK_SLACK).toMillis())
                val start = Instant.now()
                val snoring = coroutineScope {
                    val slot = async { snoreMeter.snoringFraction(snoreSlot) }
                    delay(epoch.toMillis())
                    slot.await()
                }
                val end = Instant.now()
                val samples = router.closeEpoch(newEpoch(), start, end) +
                    listOfNotNull(snoring?.let { SensorSample(SensorType.SNORING, it, start, end) })
                Log.i(TAG, "Night epoch done: ${samples.map { "${it.type}=${"%.2f".format(it.value)}" }}")
                samples.forEach { emit(it) }
            }
        } finally {
            listeners.forEach { sensorManager.unregisterListener(it) }
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Night monitoring stopped")
        }
    }

    private fun newEpoch() = Epoch(epochAggregators.map { it() }, epochQualifiers.map { it() })

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

    private class RoutingListener(private val router: EpochRouter) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = router.dispatch(event.sensor.type, event.values, event.wallClockTime())
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private companion object {
        const val TAG = "NightBreathingSource"
        const val WAKE_LOCK_TAG = "TicWatchConnector:NightBreathing"
        val WAKE_LOCK_SLACK: Duration = Duration.ofSeconds(30)
    }
}
