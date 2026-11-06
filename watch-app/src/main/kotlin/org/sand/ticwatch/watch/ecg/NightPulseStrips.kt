package org.sand.ticwatch.watch.ecg

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.bloodpressure.AsleepBloodPressureMeasurer
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.FlushCompanion
import org.sand.ticwatch.watch.text.Strings
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage
import java.io.File

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

        /** About four nights of strips every 20 min. */
        const val DEFAULT_CAPACITY = 120
    }
}

/**
 * Records a pulse strip (beats + raw pulse wave, the same [capturer] as a phone-requested rhythm strip, without its
 * screen) at each sleep measurement and queues it for the phone, which keeps it with the rhythm strips and estimates
 * blood pressure from its pulse shape. Nothing is sent from here: [NightStripSender] goes with the next sync flush.
 */
class NightPulseStripRecorder(
    private val capturer: EcgCapturer,
    private val queue: NightStripQueue,
    private val strings: Strings,
) : AsleepBloodPressureMeasurer {

    override suspend fun measure() {
        val result = capturer.capture(SilentListener)
        if (result.status != EcgCaptureStatus.COMPLETED) {
            Log.i(TAG, "No night pulse strip: ${result.status}")
            return
        }
        queue.enqueue(result.startedAt.toEpochMilli(), EcgPayloads.encodeResult(result, null, strings))
        Log.i(TAG, "Night pulse strip queued: ${result.beats.size} beat(s), ${result.wave.size} wave sample(s)")
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

/** Sends the queued night strips on [DataLayerPaths.MESSAGE_ECG_NIGHT_STRIP] at the end of each flush; stops at the first failure. */
class NightStripSender(
    private val queue: NightStripQueue,
    private val transport: DeviceLinkTransport,
) : FlushCompanion {

    override suspend fun onFlush() {
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
