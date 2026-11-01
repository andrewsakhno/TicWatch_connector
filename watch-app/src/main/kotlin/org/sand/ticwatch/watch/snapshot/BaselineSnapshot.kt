package org.sand.ticwatch.watch.snapshot

import android.util.Log
import androidx.annotation.StringRes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.sand.ticwatch.watch.R
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.AggregatorGroup
import org.sand.ticwatch.watch.sensors.QualifierProbe
import org.sand.ticwatch.watch.sensors.SensorCollector
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.sensors.WindowGate
import org.sand.ticwatch.watch.sensors.WindowMeasurer
import org.sand.ticwatch.watch.sensors.WindowObserver
import org.sand.ticwatch.watch.sensors.WindowQualifier
import org.sand.ticwatch.watch.sensors.median

/** Outcome of a snapshot; wire contract with phone-app's `SnapshotStatus` (matched by name). */
enum class SnapshotStatus(@StringRes val message: Int) {
    COMPLETED(R.string.snapshot_completed),
    NOT_WORN(R.string.put_watch_on),
    NOT_STILL(R.string.snapshot_not_still),
    NO_READINGS(R.string.snapshot_no_readings),
    BUSY(R.string.snapshot_busy),
}

/** What a running snapshot is doing now; wire contract with phone-app's `SnapshotStage` (matched by name). */
enum class SnapshotStage {
    STILLNESS_CHECK,
    MEASURING,
    SYNCING,
    SENDING_RESULT,
}

/** A running snapshot's [stage] and, while measuring, the metrics [polled] and those already [answering]. */
data class SnapshotProgress(
    val stage: SnapshotStage,
    val polled: Set<SensorType> = emptySet(),
    val answering: Set<SensorType> = emptySet(),
)

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
    /** [onProgress] hears each stage and metric change as it happens; a request answered [SnapshotStatus.BUSY] reports none. */
    suspend fun captureFullSnapshot(onProgress: (SnapshotProgress) -> Unit): SnapshotResult
}

/** Builds up one capture's [SnapshotProgress]; sensors answer on the main thread while the capture runs elsewhere. */
private class SnapshotProgressTracker(private val report: (SnapshotProgress) -> Unit) : WindowObserver {
    private var current = SnapshotProgress(SnapshotStage.STILLNESS_CHECK)

    @Synchronized
    fun startStage(stage: SnapshotStage) = publish(SnapshotProgress(stage))

    @Synchronized
    override fun onPolling(metrics: Set<SensorType>) = publish(current.copy(polled = current.polled + metrics))

    @Synchronized
    override fun onAnswering(metric: SensorType) = publish(current.copy(answering = current.answering + metric))

    private fun publish(progress: SnapshotProgress) {
        current = progress
        report(progress)
    }
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

    override suspend fun captureFullSnapshot(onProgress: (SnapshotProgress) -> Unit): SnapshotResult {
        val startedAt = clock.instant()
        if (!running.tryLock()) return finished(SnapshotStatus.BUSY, startedAt)
        return try {
            awakeRunner.runAwake(timing.stillnessProbe.plus(timing.window).plus(WAKE_SLACK)) {
                capture(startedAt, SnapshotProgressTracker(onProgress))
            }
        } finally {
            running.unlock()
        }
    }

    private suspend fun capture(startedAt: Instant, progress: SnapshotProgressTracker): SnapshotResult {
        if (!worn.isOpen()) return finished(SnapshotStatus.NOT_WORN, startedAt)
        progress.startStage(SnapshotStage.STILLNESS_CHECK)
        if (!probe.accepts(stillness(), timing.stillnessProbe)) return finished(SnapshotStatus.NOT_STILL, startedAt)
        progress.startStage(SnapshotStage.MEASURING)
        progress.onPolling(setOf(SensorType.HEART_RATE))
        val samples = coroutineScope {
            val heartRateMedian = async { medianHeartRate(progress) }
            measurer.measure(groups(), timing.window, progress) + listOfNotNull(heartRateMedian.await())
        }
        val status = if (samples.isEmpty()) SnapshotStatus.NO_READINGS else SnapshotStatus.COMPLETED
        Log.i(TAG, "Snapshot $status: ${samples.map { "${it.type}=${it.value}" }}")
        return finished(status, startedAt, samples)
    }

    private suspend fun medianHeartRate(observer: WindowObserver): SensorSample? {
        val readings = mutableListOf<Double>()
        withTimeoutOrNull(timing.window.toMillis()) {
            heartRate.heartRateSamples().collect {
                readings += it.value
                if (it.value > 0) observer.onAnswering(SensorType.HEART_RATE)
            }
        }
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
