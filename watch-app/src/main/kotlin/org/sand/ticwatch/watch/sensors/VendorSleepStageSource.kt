package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Duration
import java.time.Instant

/** Sleep stage sent as a [SensorType.SLEEP_STAGE] sample's value; [code] is the wire value (phone `SleepStageType`). */
enum class SleepStage(val code: Int) {
    AWAKE(1),
    LIGHT(2),
    DEEP(3),
    REM(4);

    companion object {
        fun fromCode(code: Int): SleepStage? = entries.firstOrNull { it.code == code }
    }
}

/** Decodes one sleep-sensor event into a stage, or null when it reports no sleep (Strategy: one layout per decoder). */
fun interface SleepStageDecoder {
    fun decode(values: FloatArray): SleepStage?
}

/**
 * Mobvoi `mobvoi_sleep` / TicMotion sleep layout: values[0] is the stage code (1 awake, 2 light,
 * 3 deep, 4 rem). UNCONFIRMED: the daytime probe log only ever showed 0 (no sleep) and 99
 * (presumably not worn / undetermined), which both decode to null - check a night's
 * `sleep_sensor.log` (DEBUG_PULL_WATCH_SLEEP_LOG) before trusting the stage codes.
 */
class MobvoiSleepStageDecoder : SleepStageDecoder {
    override fun decode(values: FloatArray): SleepStage? =
        values.getOrNull(STAGE_INDEX)?.toInt()?.let(SleepStage::fromCode)

    private companion object {
        const val STAGE_INDEX = 0
    }
}

/**
 * Turns a stream of per-event stages into completed samples: one [SensorType.SLEEP_STAGE] interval
 * per run of equal stages, and - once a non-sleep event (null) ends the night - the [SensorType.SLEEP]
 * session spanning all its stages, if it lasted [minSession]. A silence longer than [maxGap] (watch
 * off, sensor stopped) ends the night at the last event. Not thread-safe: feed it from one thread.
 */
class SleepStageSegmenter(
    private val minSession: Duration,
    private val maxGap: Duration,
) {
    private var stage: SleepStage? = null
    private var stageStart: Instant? = null
    private var sessionStart: Instant? = null
    private var lastEventAt: Instant? = null

    fun onStage(next: SleepStage?, at: Instant): List<SensorSample> {
        val previousEventAt = lastEventAt
        val completed = mutableListOf<SensorSample>()
        if (previousEventAt != null && Duration.between(previousEventAt, at) > maxGap) {
            completed += closeSession(previousEventAt)
        }
        lastEventAt = at
        if (next == stage) return completed
        if (next == null) return completed + closeSession(at)

        closeStage(at)?.let(completed::add)
        stage = next
        stageStart = at
        if (sessionStart == null) sessionStart = at
        return completed
    }

    private fun closeStage(at: Instant): SensorSample? {
        val current = stage ?: return null
        val start = stageStart ?: return null
        if (!at.isAfter(start)) return null
        return SensorSample(SensorType.SLEEP_STAGE, current.code.toDouble(), start, at)
    }

    private fun closeSession(at: Instant): List<SensorSample> {
        val lastStage = closeStage(at)
        val start = sessionStart
        stage = null
        stageStart = null
        sessionStart = null
        if (start == null) return emptyList()
        val session = SensorSample(SensorType.SLEEP, StateSessionTracker.NO_SESSION_VALUE, start, at)
            .takeIf { Duration.between(start, at) >= minSession }
        return listOfNotNull(lastStage, session)
    }
}

/**
 * Sleep stages and sessions from the Mobvoi sleep sensors. Health Services reports no ASLEEP state
 * on this watch, so this is its only sleep source. [sensorTypes] are in priority order: a lower
 * priority sensor's events are ignored while a higher one has reported within [preferenceWindow], so
 * two sensors describing the same night don't interleave. Registered non-wakeup with a long report
 * latency, so the hub batches events instead of waking the CPU through the night.
 */
class VendorSleepStageSource(
    private val sensorManager: SensorManager,
    private val sensorTypes: List<Int>,
    private val decoder: SleepStageDecoder,
    private val segmenter: SleepStageSegmenter,
    private val preferenceWindow: Duration = Duration.ofMinutes(15),
) : SampleSource {

    override fun samples(): Flow<SensorSample> = callbackFlow {
        val lastEventBySensor = mutableMapOf<Int, Instant>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val at = event.wallClockTime()
                val type = event.sensor.type
                lastEventBySensor[type] = at
                if (isShadowed(type, at, lastEventBySensor)) return
                segmenter.onStage(decoder.decode(event.values), at).forEach { trySend(it) }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorTypes.forEach { type ->
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type && !it.isWakeUpSensor }
            val registered = sensor != null && sensorManager.registerListener(
                listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_MICROS,
            )
            Log.i(TAG, "Sleep sensor $type registered=$registered")
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private fun isShadowed(type: Int, at: Instant, lastEventBySensor: Map<Int, Instant>): Boolean =
        sensorTypes.takeWhile { it != type }.any { preferred ->
            lastEventBySensor[preferred]?.let { Duration.between(it, at) <= preferenceWindow } == true
        }

    private companion object {
        const val TAG = "VendorSleepStageSource"
        val MAX_REPORT_LATENCY_MICROS = Duration.ofMinutes(5).toNanos().div(1_000L).toInt()
    }
}
