package org.sand.ticwatch.watch.bloodpressure

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.buffer.SensorSampleBuffer
import org.sand.ticwatch.watch.sensors.ReadingHistory
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.sensors.SleepState
import org.sand.ticwatch.watch.sensors.TimedReading
import org.sand.ticwatch.watch.sensors.WindowGate
import org.sand.ticwatch.watch.sensors.adaptive.HeartRateCorridorSnapshot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** Estimates on the watch when the phone can't be asked; null when nothing could be estimated. */
fun interface OfflineBloodPressureEstimator {
    suspend fun estimateAndQueue(heartRate: TimedReading?): LocalBloodPressure?
}

/**
 * [OfflineBloodPressureEstimator] from the cached phone [calibration]: needs a fresh calibration, a [rest] gate that is
 * open and a [heartRate] at most [MAX_HEART_RATE_AGE] old. RMSSD and skin temperature come from [readings] when recent.
 * The pair goes into the durable sample [buffer] (diastolic first, as the phone's systolic mapper expects its partner
 * stored), so the regular sync delivers it to the phone's history once the link is back.
 */
class CachedCalibrationOfflineEstimator(
    private val calibration: StateFlow<WatchBloodPressureCalibration?>,
    private val corridor: StateFlow<HeartRateCorridorSnapshot?>,
    private val sleepState: SleepState,
    private val readings: ReadingHistory,
    private val rest: WindowGate,
    private val estimator: LocalBloodPressureEstimator,
    private val buffer: SensorSampleBuffer,
    private val clock: Clock,
) : OfflineBloodPressureEstimator {

    override suspend fun estimateAndQueue(heartRate: TimedReading?): LocalBloodPressure? {
        val now = clock.instant()
        val cached = calibration.value?.takeUnless { it.isStale(now) } ?: return null
        val pulse = heartRate?.takeIf { Duration.between(it.at, now) <= MAX_HEART_RATE_AGE } ?: return null
        if (!rest.isOpen()) return null
        val signals = LocalWristSignals(pulse.value, recent(SensorType.HRV_RMSSD, now), recent(SensorType.SKIN_TEMPERATURE, now))
        val estimate = estimator.estimate(cached, signals, restingFloor())
        buffer.enqueue(SensorSample(SensorType.BLOOD_PRESSURE_DIASTOLIC, estimate.diastolic, now))
        buffer.enqueue(SensorSample(SensorType.BLOOD_PRESSURE_SYSTOLIC, estimate.systolic, now))
        return estimate
    }

    /** Like the phone's floor (lowest edge of the resting corridors); none inside a sleep session, so the nocturnal dip shows. */
    private fun restingFloor(): Double? =
        if (sleepState.isAsleep()) null else corridor.value?.let { minOf(it.awakeRest.lowBpm, it.sleep.lowBpm) }

    private fun recent(type: SensorType, now: Instant): Double? =
        readings.history(type).value.lastOrNull()?.takeIf { Duration.between(it.at, now) <= SIGNAL_MAX_AGE }?.value

    private companion object {
        /** Passive heart rate arrives in batches (2 a minute at night), so allow a little more than the phone's live limit. */
        val MAX_HEART_RATE_AGE: Duration = Duration.ofMinutes(5)

        /** Same lookback as the phone's `WristSignalsReader` uses for RMSSD and skin temperature. */
        val SIGNAL_MAX_AGE: Duration = Duration.ofHours(3)
    }
}

/** [AsleepBloodPressureMeasurer] that estimates locally from the newest heart rate in [readings]. */
class LocalAsleepBloodPressureMeasurer(
    private val estimator: OfflineBloodPressureEstimator,
    private val readings: ReadingHistory,
) : AsleepBloodPressureMeasurer {

    override suspend fun measure() {
        val estimate = estimator.estimateAndQueue(readings.history(SensorType.HEART_RATE).value.lastOrNull())
        if (estimate == null) {
            Log.i(TAG, "No sleep estimate: no fresh calibration, heart rate or rest")
        } else {
            Log.i(TAG, "Sleep estimate ${estimate.systolic.roundToInt()}/${estimate.diastolic.roundToInt()} queued")
        }
    }

    private companion object {
        const val TAG = "AsleepBloodPressure"
    }
}
