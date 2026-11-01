package org.sand.ticwatch.watch.snapshot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.watch.bloodpressure.PendingSampleFlusher
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.SensorSampleJson
import org.sand.ticwatch.watch.sensors.SampleSource
import org.sand.ticwatch.watch.sensors.SensorSample
import org.sand.ticwatch.watch.text.Strings
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/** What the snapshot button shows. */
sealed interface SnapshotState {
    data object Idle : SnapshotState
    data object Running : SnapshotState
    data class Done(val result: SnapshotResult, val delivered: Boolean) : SnapshotState
}

/** Starts a snapshot: the watch's button (no [requestId]) or the phone's command (its id is echoed in the result). */
fun interface SnapshotRequester {
    fun request(requestId: String?)
}

/** Receives samples for the regular sync pipeline. */
fun interface SampleSink {
    fun accept(samples: List<SensorSample>)
}

/** [SampleSource] fed through a [SampleSink]: samples handed over outside any sensor stream. */
class ChannelSampleSource : SampleSource, SampleSink {
    private val channel = Channel<SensorSample>(Channel.UNLIMITED)

    override fun samples(): Flow<SensorSample> = channel.receiveAsFlow()

    override fun accept(samples: List<SensorSample>) = samples.forEach { channel.trySend(it) }
}

/**
 * Runs [SnapshotCapturer.captureFullSnapshot] in [scope], shows its progress in [state] (and to a requesting phone), hands the samples to the
 * on-watch [display], delivers the buffered samples through [flusher] (the phone computes from fresh history, not
 * the last sync window) and sends the result on [DataLayerPaths.MESSAGE_SNAPSHOT_RESULT], where the phone stores them
 * flagged as a snapshot. When the phone is unreachable the samples go to [fallback] (the regular, unflagged sync)
 * so the measurement isn't lost.
 */
class SnapshotController(
    private val capturer: SnapshotCapturer,
    private val transport: DeviceLinkTransport,
    private val flusher: PendingSampleFlusher,
    private val display: SampleSink,
    private val fallback: SampleSink,
    private val scope: CoroutineScope,
    private val strings: Strings,
) : SnapshotRequester {

    private val current = MutableStateFlow<SnapshotState>(SnapshotState.Idle)
    val state: StateFlow<SnapshotState> = current.asStateFlow()

    private val progress = MutableStateFlow<ProgressUpdate?>(null)

    init {
        // On its own coroutine and conflated: a slow link may skip a stage but never delays the measurement.
        scope.launch { progress.filterNotNull().collect(::sendProgress) }
    }

    override fun request(requestId: String?) {
        scope.launch {
            current.value = SnapshotState.Running
            val report: (SnapshotProgress) -> Unit = { update -> requestId?.let { progress.value = ProgressUpdate(it, update) } }
            val result = capturer.captureFullSnapshot(report)
            display.accept(result.samples)
            if (result.status != SnapshotStatus.BUSY) report(SnapshotProgress(SnapshotStage.SYNCING))
            flushWithinBudget()
            if (result.status != SnapshotStatus.BUSY) report(SnapshotProgress(SnapshotStage.SENDING_RESULT))
            val delivered = transport.sendMessage(DataLayerPaths.MESSAGE_SNAPSHOT_RESULT, SnapshotPayloads.encode(result, requestId, strings))
                .onFailure { Log.w(TAG, "Snapshot result not delivered, syncing its samples unflagged", it) }
                .isSuccess
            if (!delivered) fallback.accept(result.samples)
            // A BUSY answer belongs to the second request; the running snapshot keeps the screen.
            if (result.status != SnapshotStatus.BUSY) current.value = SnapshotState.Done(result, delivered)
        }
    }

    /**
     * A backlog or an unanswered send (the link blocks on the phone's ACK, which cancellation can't interrupt) must not
     * hold the result back: past [FLUSH_BUDGET_MS] the flush carries on by itself and the result goes out anyway.
     */
    private suspend fun flushWithinBudget() {
        val flush = scope.async {
            runCatching { flusher.flush() }.onFailure { Log.w(TAG, "Flushing samples before the snapshot result failed", it) }
        }
        if (withTimeoutOrNull(FLUSH_BUDGET_MS) { flush.await() } == null) {
            Log.w(TAG, "Flush still running after $FLUSH_BUDGET_MS ms, sending the snapshot result without waiting")
        }
    }

    private suspend fun sendProgress(update: ProgressUpdate) {
        Log.i(TAG, "Snapshot progress ${update.progress}")
        transport.sendMessage(DataLayerPaths.MESSAGE_SNAPSHOT_PROGRESS, SnapshotPayloads.encodeProgress(update.requestId, update.progress))
            .onFailure { Log.w(TAG, "Snapshot progress ${update.progress} not delivered", it) }
    }

    private data class ProgressUpdate(val requestId: String, val progress: SnapshotProgress)

    private companion object {
        const val TAG = "SnapshotController"
        const val FLUSH_BUDGET_MS = 45_000L
    }
}

/** `/snapshot_result` payload, decoded by phone-app's `SnapshotPayloads`. */
object SnapshotPayloads {
    const val REQUEST_ID = "request_id"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val STARTED_AT = "started_at"
    const val FINISHED_AT = "finished_at"
    const val SAMPLES = "samples"

    /** Same keys as the blood pressure measure request, so the phone can estimate it from the snapshot's heart rate. */
    const val HEART_RATE = "heart_rate"
    const val HEART_RATE_AT = "heart_rate_at"

    const val STAGE = "stage"
    const val POLLED = "polled"
    const val ANSWERING = "answering"

    /** `/snapshot_progress` payload; metrics by `SensorType` name. */
    fun encodeProgress(requestId: String, progress: SnapshotProgress): ByteArray =
        JSONObject()
            .put(REQUEST_ID, requestId)
            .put(STAGE, progress.stage.name)
            .put(POLLED, JSONArray(progress.polled.map { it.name }))
            .put(ANSWERING, JSONArray(progress.answering.map { it.name }))
            .toString()
            .toByteArray(Charsets.UTF_8)

    /** [strings] resolves the status message the phone shows as it is. */
    fun encode(result: SnapshotResult, requestId: String?, strings: Strings): ByteArray {
        val samples = JSONArray()
        result.samples.forEach { samples.put(SensorSampleJson.encode(it)) }
        val json = JSONObject()
            .put(STATUS, result.status.name)
            .put(MESSAGE, strings.get(result.status.message))
            .put(STARTED_AT, result.startedAt.toEpochMilli())
            .put(FINISHED_AT, result.finishedAt.toEpochMilli())
            .put(SAMPLES, samples)
        requestId?.let { json.put(REQUEST_ID, it) }
        result.heartRate?.let { json.put(HEART_RATE, it.value).put(HEART_RATE_AT, it.recordedAt.toEpochMilli()) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
}
