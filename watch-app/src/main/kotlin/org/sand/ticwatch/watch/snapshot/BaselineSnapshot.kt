package org.sand.ticwatch.watch.snapshot

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.AggregatorGroup
import org.sand.ticwatch.watch.sensors.QualifierProbe
import org.sand.ticwatch.watch.sensors.SensorCollector
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.sensors.WindowGate
import org.sand.ticwatch.watch.sensors.WindowMeasurer
import org.sand.ticwatch.watch.sensors.WindowQualifier
import org.sand.ticwatch.watch.sensors.median
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Outcome of a snapshot; wire contract with phone-app's `SnapshotStatus` (matched by name). */
enum class SnapshotStatus(val message: String) {
    COMPLETED("Snapshot captured"),
    NOT_WORN("Put the watch on"),
    NOT_STILL("Keep the wrist still and retry"),
    NO_READINGS("No sensor produced a reading"),
    BUSY("A snapshot is already running"),
}

/** One Baseline Snapshot: every metric measured together in a single window, at rest. */
data class SnapshotResult(
    val status: SnapshotStatus,
    val startedAt: Instant,
    val finishedAt: Instant,
    val samples: List<SensorSample>,
) {
    val heartRate: SensorSample? get() = samples.firstOrNull { it.type == SensorType.HEART_RATE }
}

/** Takes a full snapshot of all available metrics now. */
fun interface SnapshotCapturer {
    suspend fun captureFullSnapshot(): SnapshotResult
}

/** Timing of [BaselineSnapshotCapturer]. */
class SnapshotTiming(
    /** Accelerometer + gyroscope listen this long before the window; a moving wrist aborts the snapshot. */
    val stillnessProbe: Duration,
    /** SpO2 needs ~2 min of PPG to converge, so the window can't be much shorter than the periodic one. */
    val window: Duration,
)

/**
 * [SnapshotCapturer] that checks the watch is [worn] and the wrist still ([stillness] qualifiers over
 * [SnapshotTiming.stillnessProbe]), then measures every window [groups] group (SpO2, HRV + breathing, skin and
 * ambient temperature, barometric pressure, light) in one window while the live heart-rate stream runs alongside;
 * its median becomes the snapshot's heart rate. Ignores the adaptive cadence; the body groups' own gates and
 * qualifiers still apply. One snapshot at a time: a concurrent request answers [SnapshotStatus.BUSY].
 */
class BaselineSnapshotCapturer(
    private val worn: WindowGate,
    private val stillness: () -> List<WindowQualifier>,
    private val probe: QualifierProbe,
    private val measurer: WindowMeasurer,
    private val groups: () -> List<AggregatorGroup>,
    private val heartRate: SensorCollector,
    private val awakeRunner: AwakeRunner,
    private val timing: SnapshotTiming,
    private val clock: Clock,
) : SnapshotCapturer {

    private val running = Mutex()

    override suspend fun captureFullSnapshot(): SnapshotResult {
        val startedAt = clock.instant()
        if (!running.tryLock()) return finished(SnapshotStatus.BUSY, startedAt)
        return try {
            awakeRunner.runAwake(timing.stillnessProbe.plus(timing.window).plus(WAKE_SLACK)) { capture(startedAt) }
        } finally {
            running.unlock()
        }
    }

    private suspend fun capture(startedAt: Instant): SnapshotResult {
        if (!worn.isOpen()) return finished(SnapshotStatus.NOT_WORN, startedAt)
        if (!probe.accepts(stillness(), timing.stillnessProbe)) return finished(SnapshotStatus.NOT_STILL, startedAt)
        val samples = coroutineScope {
            val heartRateMedian = async { medianHeartRate() }
            measurer.measure(groups(), timing.window) + listOfNotNull(heartRateMedian.await())
        }
        val status = if (samples.isEmpty()) SnapshotStatus.NO_READINGS else SnapshotStatus.COMPLETED
        Log.i(TAG, "Snapshot $status: ${samples.map { "${it.type}=${it.value}" }}")
        return finished(status, startedAt, samples)
    }

    private suspend fun medianHeartRate(): SensorSample? {
        val readings = mutableListOf<Double>()
        withTimeoutOrNull(timing.window.toMillis()) { heartRate.heartRateSamples().collect { readings += it.value } }
        val valid = readings.filter { it > 0 }
        return if (valid.isEmpty()) null else SensorSample(SensorType.HEART_RATE, valid.median(), clock.instant())
    }

    private fun finished(status: SnapshotStatus, startedAt: Instant, samples: List<SensorSample> = emptyList()) =
        SnapshotResult(status, startedAt, clock.instant(), samples)

    private companion object {
        const val TAG = "BaselineSnapshot"
        val WAKE_SLACK: Duration = Duration.ofSeconds(30)
    }
}
