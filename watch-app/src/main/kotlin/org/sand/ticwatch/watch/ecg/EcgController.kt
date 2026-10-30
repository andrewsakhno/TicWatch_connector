package org.sand.ticwatch.watch.ecg

import android.util.Log
import java.time.Clock
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.text.Strings
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/** Starts a rhythm recording for the phone's command (its id is echoed in progress and result). */
fun interface EcgRequester {
    fun request(requestId: String?)
}

/**
 * Runs [EcgCapturer.capture] in [scope] with the watch's recording [screen] up, sends the beats and wave recorded so
 * far on [DataLayerPaths.MESSAGE_ECG_PROGRESS] every [progressInterval] (the phone draws them live) and the whole strip
 * on [DataLayerPaths.MESSAGE_ECG_RESULT].
 */
class EcgController(
    private val capturer: EcgCapturer,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val progressInterval: Duration,
    private val recording: Duration,
    private val screen: EcgCaptureScreen,
    private val state: EcgCaptureStateSink,
    private val clock: Clock,
    private val strings: Strings,
) : EcgRequester {

    override fun request(requestId: String?) {
        scope.launch {
            val recorded = RecordedSoFar()
            var progress = EcgCaptureProgress(EcgCapturePhase.FINDING_PULSE, recording.toMillis())
            state.publish(progress)
            screen.show()
            val sender = launch { sendProgress(requestId, recorded) }
            val result = capturer.capture(object : EcgCaptureListener {
                override fun onRecordingStarted() {
                    progress = progress.copy(phase = EcgCapturePhase.RECORDING, recordingStartedAtMs = clock.millis())
                    state.publish(progress)
                }

                override fun onBeat(beat: EcgBeat) {
                    recorded.add(beat)
                    if (beat.clean) {
                        progress = progress.copy(heartRateBpm = (MS_PER_MINUTE / beat.rrMs).roundToInt())
                        state.publish(progress)
                    }
                }

                override fun onWave(sample: EcgWaveSample) = recorded.add(sample)
            })
            sender.cancel()
            state.publish(progress.copy(phase = EcgCapturePhase.FINISHED, outcome = result.status.message))
            screen.hide()
            transport.sendMessage(DataLayerPaths.MESSAGE_ECG_RESULT, EcgPayloads.encodeResult(result, requestId, strings))
                .onFailure { Log.w(TAG, "Rhythm recording not delivered", it) }
        }
    }

    private suspend fun sendProgress(requestId: String?, recorded: RecordedSoFar) {
        while (true) {
            delay(progressInterval.toMillis())
            val (beats, wave) = recorded.takeFresh()
            if (beats.isEmpty() && wave.isEmpty()) continue
            transport.sendMessage(DataLayerPaths.MESSAGE_ECG_PROGRESS, EcgPayloads.encodeProgress(beats, wave, requestId))
                .onFailure { Log.w(TAG, "Rhythm progress not delivered", it) }
        }
    }

    /** Beats and wave samples not yet sent as progress. */
    private class RecordedSoFar {
        private val beats = mutableListOf<EcgBeat>()
        private val wave = mutableListOf<EcgWaveSample>()

        @Synchronized
        fun add(beat: EcgBeat) {
            beats += beat
        }

        @Synchronized
        fun add(sample: EcgWaveSample) {
            wave += sample
        }

        @Synchronized
        fun takeFresh(): Pair<List<EcgBeat>, List<EcgWaveSample>> =
            (beats.toList() to wave.toList()).also {
                beats.clear()
                wave.clear()
            }
    }

    private companion object {
        const val TAG = "EcgController"
        const val MS_PER_MINUTE = 60_000.0
    }
}

/** `/ecg/progress` and `/ecg/result` payloads, decoded by phone-app's `EcgPayloads`. */
object EcgPayloads {
    const val REQUEST_ID = "request_id"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val STARTED_AT = "started_at"
    const val FINISHED_AT = "finished_at"
    const val BEATS = "beats"
    const val OFFSET_MS = "t"
    const val RR_MS = "rr"
    const val CLEAN = "ok"
    const val WAVE = "wave"
    const val WAVE_OFFSETS = "t"
    const val WAVE_VALUES = "v"

    fun encodeProgress(beats: List<EcgBeat>, wave: List<EcgWaveSample>, requestId: String?): ByteArray = JSONObject()
        .put(BEATS, encodeBeats(beats))
        .put(WAVE, encodeWave(wave))
        .withRequestId(requestId)
        .toString()
        .toByteArray(Charsets.UTF_8)

    /** [strings] resolves the status message the phone shows as it is. */
    fun encodeResult(result: EcgCaptureResult, requestId: String?, strings: Strings): ByteArray = JSONObject()
        .put(STATUS, result.status.name)
        .put(MESSAGE, strings.get(result.status.message))
        .put(STARTED_AT, result.startedAt.toEpochMilli())
        .put(FINISHED_AT, result.finishedAt.toEpochMilli())
        .put(BEATS, encodeBeats(result.beats))
        .put(WAVE, encodeWave(result.wave))
        .withRequestId(requestId)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun encodeBeats(beats: List<EcgBeat>): JSONArray = JSONArray().apply {
        beats.forEach { put(JSONObject().put(OFFSET_MS, it.offsetMs).put(RR_MS, it.rrMs).put(CLEAN, it.clean)) }
    }

    private fun encodeWave(wave: List<EcgWaveSample>): JSONObject = JSONObject()
        .put(WAVE_OFFSETS, JSONArray(wave.map { it.offsetMs }))
        .put(WAVE_VALUES, JSONArray(wave.map { it.value }))

    private fun JSONObject.withRequestId(requestId: String?): JSONObject = apply { requestId?.let { put(REQUEST_ID, it) } }
}
