package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import org.sand.ticwatch.watch.sensors.adaptive.MeasurementGroup
import org.sand.ticwatch.watch.sensors.adaptive.WindowCadence
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * [SampleSource] that measures with Mobvoi's vendor sensors in short periodic windows:
 * each [AggregatorGroup] is due every [WindowCadence.intervalFor] its [AggregatorGroup.group] (30 min while idle,
 * sooner while the adaptive scheduler is alerted), counted from the end of its last window; groups due within
 * [PIGGYBACK] of the one starting a window share it. Periodic rather than continuous because listening to
 * `psp_spo2` keeps the PPG emitter lit, which drains the battery when left on around the clock.
 *
 * Waits on the alarm [scheduler] for the earliest due group, or until the [cadence] changes (an alert shortened an
 * interval).
 */
class VendorWindowSampleSource(
    private val measurer: WindowMeasurer,
    private val scheduler: WindowScheduler,
    private val cadence: WindowCadence,
    private val groups: List<AggregatorGroup>,
    private val window: Duration,
    private val clock: Clock,
) : SampleSource {

    override fun samples(): Flow<SensorSample> = flow {
        val lastRun = mutableMapOf<MeasurementGroup, Instant>()
        while (true) {
            val now = clock.instant()
            if (groups.any { dueIn(it, lastRun, now) <= DUE_TOLERANCE }) {
                // Groups due soon join the window that is waking the watch anyway, so they realign after an alert.
                val due = groups.filter { dueIn(it, lastRun, now) <= PIGGYBACK }
                measurer.measure(due, window).forEach { emit(it) }
                val finishedAt = clock.instant()
                due.forEach { lastRun[it.group] = finishedAt }
            }
            awaitNextDue(lastRun)
        }
    }

    private fun dueIn(group: AggregatorGroup, lastRun: Map<MeasurementGroup, Instant>, now: Instant): Duration {
        val last = lastRun[group.group] ?: return Duration.ZERO
        return Duration.between(now, last.plus(cadence.intervalFor(group.group)))
    }

    private suspend fun awaitNextDue(lastRun: Map<MeasurementGroup, Instant>) {
        val wait = groups.minOf { dueIn(it, lastRun, clock.instant()) }
        if (wait <= DUE_TOLERANCE) return
        merge(flow { scheduler.awaitNext(wait); emit(Unit) }, cadence.changes).first()
    }

    private companion object {
        /** The alarm window: a group due this soon starts a window now. */
        val DUE_TOLERANCE: Duration = Duration.ofMinutes(1)

        /** Groups due within this long join a window another group started. */
        val PIGGYBACK: Duration = Duration.ofMinutes(5)
    }
}

/** Measures [AggregatorGroup]s together for one window and returns their results. */
fun interface WindowMeasurer {
    suspend fun measure(groups: List<AggregatorGroup>, window: Duration): List<SensorSample>
}

/**
 * [WindowMeasurer] on [SensorManager] listeners. Holds a partial wake lock for the window only: these are
 * non-wakeup sensors, so their events are dropped while the application processor sleeps.
 *
 * Each [AggregatorGroup] only joins a window its [AggregatorGroup.gate] allows, checked at the
 * window start and again at its end (a body reading taken while the watch came off the wrist is
 * discarded); a window no group joins is skipped entirely, wake lock included.
 */
class SensorWindowMeasurer(
    private val sensorManager: SensorManager,
    private val powerManager: PowerManager,
) : WindowMeasurer {

    override suspend fun measure(groups: List<AggregatorGroup>, window: Duration): List<SensorSample> {
        val openGroups = groups.filter { it.gate.isOpen() }
        if (openGroups.isEmpty()) {
            Log.i(TAG, "Vendor window skipped: every group is gated off")
            return emptyList()
        }
        val aggregatorsByGroup = openGroups.associateWith { group -> group.factories.map { it() } }
        val qualifiersByGroup = openGroups.associateWith { group -> group.qualifiers.map { it() } }
        val windowListeners = aggregatorsByGroup.values.flatten() + qualifiersByGroup.values.flatten()
        val listeners = windowListeners.mapNotNull { windowListener ->
            val sensor = nonWakeupSensor(windowListener.sensorType)
                ?: return@mapNotNull null.also { Log.w(TAG, "Sensor ${windowListener.sensorType} not found") }
            AggregatingListener(windowListener).takeIf {
                sensorManager.registerListener(it, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        val windowStart = Instant.now()
        wakeLock.acquire(window.plus(WAKE_LOCK_SLACK).toMillis())
        try {
            delay(window.toMillis())
        } finally {
            listeners.forEach { sensorManager.unregisterListener(it) }
            if (wakeLock.isHeld) wakeLock.release()
        }
        val windowEnd = Instant.now()

        val samples = aggregatorsByGroup
            .filterKeys { group -> group.gate.isOpen() && qualifiersByGroup.getValue(group).all { it.accepts() } }
            .values.flatten()
            .mapNotNull { it.result(windowStart, windowEnd) }
        Log.i(TAG, "Vendor window done: ${samples.map { "${it.type}=${it.value}" }}")
        return samples
    }

    private fun nonWakeupSensor(type: Int): Sensor? =
        sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type && !it.isWakeUpSensor }

    private class AggregatingListener(private val windowListener: WindowSensorListener) : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = windowListener.onEvent(event.values, event.wallClockTime())
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private companion object {
        const val TAG = "VendorWindowSource"
        const val WAKE_LOCK_TAG = "TicWatchConnector:VendorWindow"
        val WAKE_LOCK_SLACK: Duration = Duration.ofSeconds(10)
    }
}

/** Whether an [AggregatorGroup] may measure right now. */
fun interface WindowGate {
    fun isOpen(): Boolean

    companion object {
        val ALWAYS = WindowGate { true }
    }
}

/**
 * Aggregators that are measured together under one [gate], kept only if every [qualifiers] one accepts the window;
 * paced as [group] by the adaptive cadence.
 */
class AggregatorGroup(
    val group: MeasurementGroup,
    val factories: List<() -> VendorReadingAggregator>,
    val gate: WindowGate = WindowGate.ALWAYS,
    val qualifiers: List<() -> WindowQualifier> = emptyList(),
)

/**
 * Converts [SensorEvent.timestamp] (elapsed-realtime nanos) to wall-clock time. Some vendor sensors
 * (`mobvoi_sleep`) stamp events with a near-constant value instead of the measurement time, which
 * would date them weeks back; an event older than [MAX_PLAUSIBLE_EVENT_AGE] (well past any batching
 * latency) or from the future is dated by its arrival instead.
 */
internal fun SensorEvent.wallClockTime(): Instant {
    val ageNanos = SystemClock.elapsedRealtimeNanos() - timestamp
    val now = Instant.now()
    if (ageNanos < 0 || ageNanos > MAX_PLAUSIBLE_EVENT_AGE.toNanos()) return now
    return now.minusNanos(ageNanos)
}

private val MAX_PLAUSIBLE_EVENT_AGE: Duration = Duration.ofMinutes(15)
