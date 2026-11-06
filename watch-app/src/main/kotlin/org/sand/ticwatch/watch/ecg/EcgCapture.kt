package org.sand.ticwatch.watch.ecg

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.annotation.StringRes
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import org.sand.ticwatch.watch.R
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.RrIntervalReading
import org.sand.ticwatch.watch.sensors.VendorSensorTypes
import org.sand.ticwatch.watch.sensors.WindowGate

/** Outcome of a rhythm recording; wire contract with phone-app's `EcgCaptureStatus` (matched by name). */
enum class EcgCaptureStatus(@StringRes val message: Int) {
    COMPLETED(R.string.ecg_completed),
    NOT_WORN(R.string.put_watch_on),
    NO_BEATS(R.string.ecg_no_beats),
    BUSY(R.string.ecg_busy),
    DECLINED(R.string.ecg_declined),
}

/** One heartbeat: [offsetMs] from the recording start (running sum of intervals), the interval before it, its quality. */
data class EcgBeat(val offsetMs: Long, val rrMs: Double, val clean: Boolean)

/** One raw PPG reading, [offsetMs] from the recording start by the wall clock. */
data class EcgWaveSample(val offsetMs: Long, val value: Long)

/** A finished rhythm recording. */
data class EcgCaptureResult(
    val status: EcgCaptureStatus,
    val startedAt: Instant,
    val finishedAt: Instant,
    val beats: List<EcgBeat>,
    val wave: List<EcgWaveSample> = emptyList(),
)

/** Follows a recording while it runs. */
interface EcgCaptureListener {
    /** The pulse locked on: the strip's clock starts. */
    fun onRecordingStarted()

    fun onBeat(beat: EcgBeat)

    fun onWave(sample: EcgWaveSample)
}

/** Streams beat-to-beat intervals while collected. */
fun interface RrBeatSource {
    fun beats(): Flow<RrIntervalReading>
}

/**
 * `psp_rri`: the vendor PPG pipeline's beat-to-beat interval, one event per detected beat. The watch has no ECG
 * electrodes, so this is the closest thing to a cardiogram it can record: the rhythm, not the electrical waveform.
 */
class VendorRrBeatSource(
    private val sensorManager: SensorManager,
    private val sensorType: Int = VendorSensorTypes.RR_INTERVAL,
) : RrBeatSource {

    override fun beats(): Flow<RrIntervalReading> = callbackFlow {
        val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == sensorType && !it.isWakeUpSensor }
        if (sensor == null) {
            Log.w(TAG, "Sensor $sensorType not found")
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                RrIntervalReading.decode(event.values)?.let { trySend(it) }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        // Fastest delivery: the phone draws the beats while they are recorded.
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private companion object {
        const val TAG = "VendorRrBeatSource"
    }
}

/** Timing of [RrEcgCapturer]. */
class EcgTiming(
    /** The PPG pipeline needs a few seconds to lock on; the recording starts at the first beat. */
    val warmUp: Duration,
    val recording: Duration,
)

/** Records a rhythm strip, telling [listener] about every beat and wave sample as it arrives. */
fun interface EcgCapturer {
    suspend fun capture(listener: EcgCaptureListener): EcgCaptureResult
}

/**
 * [EcgCapturer] over [beats] and the raw [wave]: checks the watch is [worn], waits up to [EcgTiming.warmUp] for the
 * first beat, then records [EcgTiming.recording] of beats with the pulse wave under them. One recording at a time: a
 * concurrent request answers [EcgCaptureStatus.BUSY].
 */
class RrEcgCapturer(
    private val worn: WindowGate,
    private val beats: RrBeatSource,
    private val wave: PpgWaveSource,
    private val awakeRunner: AwakeRunner,
    private val timing: EcgTiming,
    private val clock: Clock,
) : EcgCapturer {

    private val running = Mutex()

    override suspend fun capture(listener: EcgCaptureListener): EcgCaptureResult {
        val requestedAt = clock.instant()
        if (!running.tryLock()) return EcgCaptureResult(EcgCaptureStatus.BUSY, requestedAt, requestedAt, emptyList())
        return try {
            awakeRunner.runAwake(timing.warmUp.plus(timing.recording).plus(WAKE_SLACK)) { record(requestedAt, listener) }
        } finally {
            running.unlock()
        }
    }

    private suspend fun record(requestedAt: Instant, listener: EcgCaptureListener): EcgCaptureResult {
        if (!worn.isOpen()) return EcgCaptureResult(EcgCaptureStatus.NOT_WORN, requestedAt, clock.instant(), emptyList())
        val strip = StripRecorder(listener, timing.recording.toMillis())
        val recordingMs = timing.recording.toMillis()
        coroutineScope {
            val waveJob = launch { wave.samples().collect(strip::onWave) }
            // One registration for the whole strip: re-registering would restart the PPG lock-on.
            withTimeoutOrNull(timing.warmUp.plus(timing.recording).toMillis()) {
                beats.beats().takeWhile { strip.elapsedMs < recordingMs }.collect { strip.onBeat(it, clock.instant()) }
            }
            waveJob.cancel()
        }
        val recorded = strip.beats()
        val status = if (recorded.size < MIN_BEATS) EcgCaptureStatus.NO_BEATS else EcgCaptureStatus.COMPLETED
        Log.i(TAG, "Rhythm recording $status: ${recorded.size} beat(s), ${strip.wave().size} wave sample(s)")
        return EcgCaptureResult(status, strip.startedAt ?: requestedAt, clock.instant(), recorded, strip.wave())
    }

    /**
     * One strip's state, fed from the beat and the wave collectors. Wave samples that arrive before the first beat
     * are held for [PRE_START_BUFFER_MS], since the strip starts one interval before that beat.
     */
    private class StripRecorder(private val listener: EcgCaptureListener, private val recordingMs: Long) {
        private val lock = Any()
        private val beats = mutableListOf<EcgBeat>()
        private val wave = mutableListOf<EcgWaveSample>()
        private val early = ArrayDeque<PpgSample>()

        @Volatile
        var startedAt: Instant? = null
            private set

        @Volatile
        var elapsedMs = 0.0
            private set

        fun onBeat(reading: RrIntervalReading, now: Instant) {
            val beat = synchronized(lock) {
                val first = startedAt == null
                // The first interval ends at its beat: the strip starts one interval earlier.
                if (first) startedAt = now.minusMillis(reading.millis.toLong())
                elapsedMs += reading.millis
                val beat = EcgBeat(elapsedMs.toLong(), reading.millis, reading.clean)
                beats += beat
                if (first) {
                    listener.onRecordingStarted()
                    while (early.isNotEmpty()) accept(early.removeFirst())
                }
                beat
            }
            listener.onBeat(beat)
        }

        fun onWave(sample: PpgSample) = synchronized(lock) {
            if (startedAt == null) {
                early.addLast(sample)
                while (sample.atEpochMs - early.first().atEpochMs > PRE_START_BUFFER_MS) early.removeFirst()
            } else {
                accept(sample)
            }
        }

        private fun accept(sample: PpgSample) {
            val offset = sample.atEpochMs - (startedAt ?: return).toEpochMilli()
            if (offset < 0 || offset >= recordingMs) return
            val waveSample = EcgWaveSample(offset, sample.value)
            wave += waveSample
            listener.onWave(waveSample)
        }

        fun beats(): List<EcgBeat> = synchronized(lock) { beats.toList() }

        fun wave(): List<EcgWaveSample> = synchronized(lock) { wave.toList() }
    }

    private companion object {
        const val TAG = "RrEcgCapturer"
        const val MIN_BEATS = 5
        val WAKE_SLACK: Duration = Duration.ofSeconds(15)

        /** Longer than any beat interval plus the sensor's batching. */
        const val PRE_START_BUFFER_MS = 3_000L
    }
}
