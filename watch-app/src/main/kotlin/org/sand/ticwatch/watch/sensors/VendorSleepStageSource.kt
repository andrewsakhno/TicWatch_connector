package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
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
 * Mobvoi `mobvoi_sleep` / TicMotion sleep layout: values[0] is a state code, sent only when it
 * changes. From the first full night (2026-10-26, in bed ~00:10, up ~06:45): 6 before falling
 * asleep, 7 at sleep onset and on a 3-min wake mid-night, then 9/10/8 cycling until 99 when the
 * night ended; 0 = no sleep. Inferred, not vendor-documented: 7 awake, 8 REM (longer runs towards
 * morning, ~90 min apart), 9 light (most of the night), 10 deep (front-loaded). 6, 99 and anything
 * else (1 was seen while walking) decode to null, i.e. not asleep.
 */
class MobvoiSleepStageDecoder : SleepStageDecoder {
    override fun decode(values: FloatArray): SleepStage? =
        values.getOrNull(STAGE_INDEX)?.toInt()?.let(STAGE_BY_VENDOR_CODE::get)

    private companion object {
        const val STAGE_INDEX = 0
        const val VENDOR_AWAKE = 7
        const val VENDOR_REM = 8
        const val VENDOR_LIGHT = 9
        const val VENDOR_DEEP = 10

        val STAGE_BY_VENDOR_CODE: Map<Int, SleepStage> = mapOf(
            VENDOR_AWAKE to SleepStage.AWAKE,
            VENDOR_REM to SleepStage.REM,
            VENDOR_LIGHT to SleepStage.LIGHT,
            VENDOR_DEEP to SleepStage.DEEP,
        )
    }
}

/**
 * The night in progress: everything [SleepStageSegmenter] must not lose across a service restart. A wake inside
 * it (the sensor's AWAKE, or a non-sleep event) is an [SleepStage.AWAKE] stage. [sensorLeftSleep] = the last event
 * reported no sleep at all; [slept] = the finished non-AWAKE stages.
 */
data class OpenSleepSession(
    val start: Instant,
    val stage: SleepStage,
    val stageStart: Instant,
    val lastEventAt: Instant,
    val slept: Duration,
    val sensorLeftSleep: Boolean,
)

/**
 * Turns a stream of per-event stages into completed samples: one [SensorType.SLEEP_STAGE] interval
 * per run of equal stages, and - once the night is over - the [SensorType.SLEEP] session spanning all its
 * stages, if it holds [minSession] of actual sleep. A wake no longer than [wakeMerge] allows stays inside the
 * session as an AWAKE stage; a longer one ends it where the wake began. A silence longer than [maxGap] (watch
 * off, sensor stopped) ends it at the last event, as does [endSession] on outside evidence. Not thread-safe:
 * the caller serializes access.
 */
class SleepStageSegmenter(
    private val minSession: Duration,
    private val maxGap: Duration,
    private val wakeMerge: WakeMergePolicy,
) {
    /** Persist this and [restore] it after a restart, or the night is lost. */
    var openSession: OpenSleepSession? = null
        private set

    /** Whether the sensor reports the wearer inside a night (its own AWAKE stage included). */
    val asleep: Boolean get() = openSession?.sensorLeftSleep == false

    fun restore(session: OpenSleepSession?) {
        openSession = session
    }

    fun onStage(next: SleepStage?, at: Instant): List<SensorSample> {
        val completed = expire(at).toMutableList()
        val current = openSession
        if (current == null) {
            // Only a sleep state opens a session; AWAKE outside one is just daytime noise.
            if (next != null && next != SleepStage.AWAKE) {
                openSession = OpenSleepSession(at, next, at, at, Duration.ZERO, sensorLeftSleep = false)
            }
            return completed
        }
        val reported = next ?: SleepStage.AWAKE
        if (reported == current.stage) {
            openSession = current.copy(lastEventAt = at, sensorLeftSleep = next == null)
            return completed
        }
        stageSample(current, at)?.let(completed::add)
        openSession = current.copy(
            stage = reported,
            stageStart = at,
            lastEventAt = at,
            slept = current.slept + sleptIn(current, at),
            sensorLeftSleep = next == null,
        )
        return completed
    }

    /** Ends the session if, by [now], its wake outlasted [wakeMerge] or the sensor has been silent past [maxGap]. */
    fun expire(now: Instant): List<SensorSample> {
        val current = openSession ?: return emptyList()
        val expired = if (current.stage == SleepStage.AWAKE) {
            Duration.between(current.stageStart, now) > wakeMerge.maxWake(current.stageStart)
        } else {
            Duration.between(current.lastEventAt, now) > maxGap
        }
        return if (expired) endSession() else emptyList()
    }

    /** Ends the session at its last sleep event: where the wake began, else the sensor's last event. */
    fun endSession(): List<SensorSample> {
        val current = openSession ?: return emptyList()
        openSession = null
        if (current.stage == SleepStage.AWAKE) return listOfNotNull(sessionSample(current, current.stageStart, current.slept))
        val slept = current.slept + sleptIn(current, current.lastEventAt)
        return listOfNotNull(stageSample(current, current.lastEventAt), sessionSample(current, current.lastEventAt, slept))
    }

    private fun sleptIn(session: OpenSleepSession, until: Instant): Duration =
        if (session.stage == SleepStage.AWAKE || !until.isAfter(session.stageStart)) Duration.ZERO
        else Duration.between(session.stageStart, until)

    private fun stageSample(session: OpenSleepSession, until: Instant): SensorSample? {
        if (!until.isAfter(session.stageStart)) return null
        return SensorSample(SensorType.SLEEP_STAGE, session.stage.code.toDouble(), session.stageStart, until)
    }

    private fun sessionSample(session: OpenSleepSession, end: Instant, slept: Duration): SensorSample? {
        if (slept < minSession) return null
        return SensorSample(SensorType.SLEEP, StateSessionTracker.NO_SESSION_VALUE, session.start, end)
    }
}

/**
 * Sleep stages and sessions from the Mobvoi sleep sensors. Health Services reports no ASLEEP state
 * on this watch, so this is its only sleep source. [sensorTypes] are in priority order: a lower
 * priority sensor's events are ignored while a higher one has reported within [preferenceWindow], so
 * two sensors describing the same night don't interleave. Registered non-wakeup with a long report
 * latency, so the hub batches events instead of waking the CPU through the night. Every event also
 * updates [sleepState], which gates the night-time breathing monitor.
 *
 * The sensor often just stops when the wearer gets up, so while a session is open it is re-checked every
 * [checkInterval] on [scheduler]: the hub's batch is flushed first (events may be waiting in it), then the
 * session ends if [wakeEvidence] says the wearer is up or the segmenter's timeouts ran out. The open session
 * lives in [store], so a restart or a flat battery resumes the night, or closes it on the first check.
 */
class VendorSleepStageSource(
    private val sensorManager: SensorManager,
    private val sensorTypes: List<Int>,
    private val decoder: SleepStageDecoder,
    private val segmenter: SleepStageSegmenter,
    private val sleepState: SleepStateSink,
    private val store: OpenSleepSessionStore,
    private val scheduler: WindowScheduler,
    private val wakeEvidence: List<WakeEvidence>,
    private val checkInterval: Duration,
    private val clock: Clock,
    private val preferenceWindow: Duration = Duration.ofMinutes(15),
) : SampleSource {

    override fun samples(): Flow<SensorSample> = callbackFlow {
        val restored = withContext(Dispatchers.IO) { store.load() }
        val sessionOpen = MutableStateFlow(restored != null)
        synchronized(segmenter) {
            segmenter.restore(restored)
            restored?.let { sleepState.update(segmenter.asleep, it.lastEventAt) }
        }

        fun publish(completed: List<SensorSample>, at: Instant) {
            completed.forEach { trySend(it) }
            sleepState.update(segmenter.asleep, at)
            store.save(segmenter.openSession)
            sessionOpen.value = segmenter.openSession != null
        }

        val lastEventBySensor = mutableMapOf<Int, Instant>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val at = event.wallClockTime()
                val type = event.sensor.type
                lastEventBySensor[type] = at
                if (isShadowed(type, at, lastEventBySensor)) return
                synchronized(segmenter) { publish(segmenter.onStage(decoder.decode(event.values), at), at) }
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
        launch {
            while (true) {
                sessionOpen.first { it }
                sensorManager.flush(listener)
                // Inside the alarm's hand-off wake lock.
                delay(FLUSH_SETTLE.toMillis())
                val now = clock.instant()
                synchronized(segmenter) {
                    val up = wakeEvidence.any { it.isUp() }
                    val completed = if (up) segmenter.endSession() else segmenter.expire(now)
                    if (segmenter.openSession == null) {
                        Log.i(TAG, "Open sleep session closed by its check: wearerUp=$up, samples=${completed.size}")
                        publish(completed, now)
                    }
                }
                if (sessionOpen.value) scheduler.awaitNext(checkInterval)
            }
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
        val FLUSH_SETTLE: Duration = Duration.ofSeconds(3)
    }
}
