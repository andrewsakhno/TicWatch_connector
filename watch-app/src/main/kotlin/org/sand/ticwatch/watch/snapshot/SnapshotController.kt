package org.sand.ticwatch.watch.snapshot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.SensorSampleJson
import org.sand.ticwatch.watch.sensors.SampleSource
import org.sand.ticwatch.watch.sensors.SensorSample
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
 * Runs [SnapshotCapturer.captureFullSnapshot] in [scope], shows its progress in [state], hands the samples to the
 * on-watch [display] and sends the result on [DataLayerPaths.MESSAGE_SNAPSHOT_RESULT], where the phone stores them
 * flagged as a snapshot. When the phone is unreachable the samples go to [fallback] (the regular, unflagged sync)
 * so the measurement isn't lost.
 */
class SnapshotController(
    private val capturer: SnapshotCapturer,
    private val transport: DeviceLinkTransport,
    private val display: SampleSink,
    private val fallback: SampleSink,
    private val scope: CoroutineScope,
) : SnapshotRequester {

    private val current = MutableStateFlow<SnapshotState>(SnapshotState.Idle)
    val state: StateFlow<SnapshotState> = current.asStateFlow()

    override fun request(requestId: String?) {
        scope.launch {
            current.value = SnapshotState.Running
            val result = capturer.captureFullSnapshot()
            display.accept(result.samples)
            val delivered = transport.sendMessage(DataLayerPaths.MESSAGE_SNAPSHOT_RESULT, SnapshotPayloads.encode(result, requestId))
                .onFailure { Log.w(TAG, "Snapshot result not delivered, syncing its samples unflagged", it) }
                .isSuccess
            if (!delivered) fallback.accept(result.samples)
            // A BUSY answer belongs to the second request; the running snapshot keeps the screen.
            if (result.status != SnapshotStatus.BUSY) current.value = SnapshotState.Done(result, delivered)
        }
    }

    private companion object {
        const val TAG = "SnapshotController"
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

    fun encode(result: SnapshotResult, requestId: String?): ByteArray {
        val samples = JSONArray()
        result.samples.forEach { samples.put(SensorSampleJson.encode(it)) }
        val json = JSONObject()
            .put(STATUS, result.status.name)
            .put(MESSAGE, result.status.message)
            .put(STARTED_AT, result.startedAt.toEpochMilli())
            .put(FINISHED_AT, result.finishedAt.toEpochMilli())
            .put(SAMPLES, samples)
        requestId?.let { json.put(REQUEST_ID, it) }
        result.heartRate?.let { json.put(HEART_RATE, it.value).put(HEART_RATE_AT, it.recordedAt.toEpochMilli()) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }
}
