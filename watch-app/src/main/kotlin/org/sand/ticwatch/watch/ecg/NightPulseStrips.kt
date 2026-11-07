package org.sand.ticwatch.watch.ecg

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.bloodpressure.AsleepBloodPressureMeasurer
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.FlushCompanion
import org.sand.ticwatch.watch.power.AwakeRunner
import org.sand.ticwatch.watch.sensors.SleepStage
import org.sand.ticwatch.watch.sensors.SleepStageState
import org.sand.ticwatch.watch.text.Strings
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Durable queue of encoded night strips waiting for the phone, oldest first. */
interface NightStripQueue {
    suspend fun enqueue(key: Long, payload: ByteArray)
    suspend fun pending(): List<Pair<Long, ByteArray>>
    suspend fun remove(key: Long)
}

/** [NightStripQueue] as one file per strip in [directory] (named by start time), so a restart or flat battery keeps them. */
class FileNightStripQueue(private val directory: File, private val capacity: Int = DEFAULT_CAPACITY) : NightStripQueue {

    override suspend fun enqueue(key: Long, payload: ByteArray) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        File(directory, "$key$SUFFIX").writeBytes(payload)
        // A phone gone for days must not fill the watch: the oldest strips go first.
        files().dropLast(capacity).forEach { it.delete() }
    }

    override suspend fun pending(): List<Pair<Long, ByteArray>> = withContext(Dispatchers.IO) {
        files().mapNotNull { file -> keyOf(file)?.let { it to file.readBytes() } }
    }

    override suspend fun remove(key: Long) {
        withContext(Dispatchers.IO) { File(directory, "$key$SUFFIX").delete() }
    }

    private fun files(): List<File> =
        directory.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty().sortedBy { keyOf(it) ?: Long.MAX_VALUE }

    private fun keyOf(file: File): Long? = file.name.removeSuffix(SUFFIX).toLongOrNull()

    private companion object {
        const val SUFFIX = ".json"

        /** Several nights of adaptive strips (at most one per alert spacing), should the phone stay away. */
        const val DEFAULT_CAPACITY = 120
    }
}

/** Why a night pulse strip is worth its PPG minute now. */
enum class NightStripReason {
    /** A settled deep-sleep stretch: the cleanest, most comparable pulse shape of the night. */
    DEEP_SLEEP,

    /** Heart rate outside the night corridor at rest: recorded out of turn, what the pulse looks like matters most. */
    HEART_RATE_ALERT,
}

/** Spacing and settling of adaptive night strips. */
data class NightStripTiming(
    /** At least this long between two deep-sleep strips. */
    val deepSleepSpacing: Duration,
    /** At least this long between two strips while the heart rate alert lasts. */
    val alertSpacing: Duration,
    /** The deep stage must have lasted this long (stage events arrive batched, minutes late). */
    val deepSleepSettle: Duration,
)

/** Decides whether a night strip is due; told when one was recorded. */
interface NightStripPolicy {
    fun reasonAt(now: Instant): NightStripReason?
    fun recorded(at: Instant)
}

/**
 * [NightStripPolicy] for adaptive collection: a strip when [heartRateAlert] (the resting heart-rate trigger's ALERT)
 * holds, at most every [NightStripTiming.alertSpacing]; otherwise only in a deep stage of [stages] settled for
 * [NightStripTiming.deepSleepSettle], at most every [NightStripTiming.deepSleepSpacing]. Watches without stages get
 * only alert strips. The last strip is kept in memory: a service restart may record one early, never loses data.
 */
class AdaptiveNightStripPolicy(
    private val stages: SleepStageState,
    private val heartRateAlert: () -> Boolean,
    private val timing: NightStripTiming,
) : NightStripPolicy {

    @Volatile
    private var lastRecordedAt: Instant? = null

    override fun reasonAt(now: Instant): NightStripReason? = when {
        heartRateAlert() && spacedBy(timing.alertSpacing, now) -> NightStripReason.HEART_RATE_ALERT
        isSettledDeepSleep(now) && spacedBy(timing.deepSleepSpacing, now) -> NightStripReason.DEEP_SLEEP
        else -> null
    }

    override fun recorded(at: Instant) {
        lastRecordedAt = at
    }

    private fun isSettledDeepSleep(now: Instant): Boolean {
        val current = stages.current() ?: return false
        return current.stage == SleepStage.DEEP && Duration.between(current.since, now) >= timing.deepSleepSettle
    }

    private fun spacedBy(spacing: Duration, now: Instant): Boolean =
        lastRecordedAt?.let { Duration.between(it, now) >= spacing } ?: true
}

/**
 * Records a pulse strip (beats + raw pulse wave, the same [capturer] as a phone-requested rhythm strip, without its
 * screen) when the [policy] finds one due and the [stillness] check confirms a still wrist (movement ruins a pulse
 * shape), and queues it for the phone, which keeps it with the rhythm strips and estimates blood pressure from its
 * pulse shape. Asked at each sleep measurement and, out of turn, by [HeartRateAlertNightStrips]. Nothing is sent from
 * here: [NightStripSender] goes with the next sync flush after the night.
 */
class NightPulseStripRecorder(
    private val capturer: EcgCapturer,
    private val queue: NightStripQueue,
    private val policy: NightStripPolicy,
    private val stillness: suspend () -> Boolean,
    private val awakeRunner: AwakeRunner,
    private val awakeTimeout: Duration,
    private val clock: Clock,
    private val strings: Strings,
) : AsleepBloodPressureMeasurer {

    private val recording = Mutex()

    override suspend fun measure() {
        // An alert strip and a scheduled measurement may ask at once: one strip is enough.
        if (!recording.tryLock()) return
        try {
            val reason = policy.reasonAt(clock.instant()) ?: return
            awakeRunner.runAwake(awakeTimeout) { record(reason) }
        } finally {
            recording.unlock()
        }
    }

    private suspend fun record(reason: NightStripReason) {
        if (!stillness()) {
            Log.i(TAG, "Wrist not still, no night pulse strip ($reason)")
            return
        }
        val result = capturer.capture(SilentListener)
        if (result.status != EcgCaptureStatus.COMPLETED) {
            Log.i(TAG, "No night pulse strip ($reason): ${result.status}")
            return
        }
        policy.recorded(result.startedAt)
        queue.enqueue(result.startedAt.toEpochMilli(), EcgPayloads.encodeResult(result, null, strings))
        Log.i(TAG, "Night pulse strip queued ($reason): ${result.beats.size} beat(s), ${result.wave.size} wave sample(s)")
    }

    private object SilentListener : EcgCaptureListener {
        override fun onRecordingStarted() = Unit
        override fun onBeat(beat: EcgBeat) = Unit
        override fun onWave(sample: EcgWaveSample) = Unit
    }

    private companion object {
        const val TAG = "NightPulseStrip"
    }
}

/**
 * Records a strip out of turn as soon as [alerts] (the resting heart-rate trigger's ALERT) switches on while [enabled]
 * (night blood pressure on, asleep, worn); the [recorder]'s policy still spaces them.
 */
class HeartRateAlertNightStrips(
    private val alerts: Flow<Boolean>,
    private val enabled: () -> Boolean,
    private val recorder: AsleepBloodPressureMeasurer,
) {
    suspend fun run() {
        alerts.distinctUntilChanged().filter { it }.collect { if (enabled()) recorder.measure() }
    }
}

/**
 * Sends the queued night strips on [DataLayerPaths.MESSAGE_ECG_NIGHT_STRIP] at the end of a flush; stops at the first
 * failure. Holds them while [holdBack] (asleep): ~100 KB strips over RFCOMM in the night would page a sleeping phone
 * for minutes; the flush that follows the session's end (the finished SLEEP sample flushes at once) sends them in one go.
 */
class NightStripSender(
    private val queue: NightStripQueue,
    private val transport: DeviceLinkTransport,
    private val holdBack: () -> Boolean,
) : FlushCompanion {

    override suspend fun onFlush() {
        if (holdBack()) return
        for ((key, payload) in queue.pending()) {
            val sent = transport.sendMessage(DataLayerPaths.MESSAGE_ECG_NIGHT_STRIP, payload)
            if (sent.isFailure) {
                Log.w(TAG, "Night strip $key not delivered, kept for the next flush", sent.exceptionOrNull())
                return
            }
            queue.remove(key)
        }
    }

    private companion object {
        const val TAG = "NightStripSender"
    }
}
