package org.sand.ticwatch.phone.snapshot

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.sand.ticwatch.phone.datalayer.BloodPressureRequestHandler
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.WatchReplyAwaiter
import org.sand.ticwatch.phone.datalayer.WatchReplySink
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorSampleJson
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/** Outcome of a watch snapshot; wire contract with watch-app's `SnapshotStatus` (matched by name). */
enum class SnapshotStatus {
    COMPLETED,
    NOT_WORN,
    NOT_STILL,
    NO_READINGS,
    BUSY,
}

/** A Baseline Snapshot the watch took: every metric measured together in one window, samples flagged [SensorSample.snapshot]. */
data class WatchSnapshotResult(
    val status: SnapshotStatus,
    val message: String,
    val startedAt: Instant,
    val finishedAt: Instant,
    val samples: List<SensorSample>,
    val requestId: String?,
)

/** `/snapshot_result` payload, encoded by watch-app's `SnapshotPayloads`. */
object SnapshotPayloads {
    const val REQUEST_ID = "request_id"
    const val STATUS = "status"
    const val MESSAGE = "message"
    const val STARTED_AT = "started_at"
    const val FINISHED_AT = "finished_at"
    const val SAMPLES = "samples"

    fun decode(text: String): WatchSnapshotResult {
        val json = JSONObject(text)
        val entries = json.getJSONArray(SAMPLES)
        return WatchSnapshotResult(
            status = SnapshotStatus.valueOf(json.getString(STATUS)),
            message = json.optString(MESSAGE),
            startedAt = Instant.ofEpochMilli(json.getLong(STARTED_AT)),
            finishedAt = Instant.ofEpochMilli(json.getLong(FINISHED_AT)),
            samples = (0 until entries.length()).map { SensorSampleJson.decode(entries.getJSONObject(it)).copy(snapshot = true) },
            requestId = json.optString(REQUEST_ID).takeIf { it.isNotEmpty() },
        )
    }

    fun encodeRequest(requestId: String): ByteArray = JSONObject().put(REQUEST_ID, requestId).toString().toByteArray(Charsets.UTF_8)
}

/** Latest snapshot result, for the Metrics tab. */
interface SnapshotResultLog {
    val latest: StateFlow<WatchSnapshotResult?>
    fun record(result: WatchSnapshotResult)
}

/** Process-wide: results arrive in whichever link service the transport woke, while the screen lives in the activity. */
object ProcessSnapshotResults : SnapshotResultLog {
    private val current = MutableStateFlow<WatchSnapshotResult?>(null)
    override val latest: StateFlow<WatchSnapshotResult?> = current.asStateFlow()

    override fun record(result: WatchSnapshotResult) {
        current.value = result
    }
}

/** Handles the watch's `/snapshot_result`; false when its samples weren't stored, so the watch keeps them. */
fun interface SnapshotResultReceiver {
    suspend fun onSnapshotResult(payload: ByteArray): Boolean
}

/**
 * Stores a snapshot's samples flagged `is_snapshot` through the regular ingest (history + Health Connect + derivers),
 * estimates blood pressure from the snapshot's heart rate (the payload carries the measure request's keys), records
 * the result for the screen and answers whoever is waiting on its request id (the MCP tool).
 */
class StoringSnapshotResultReceiver(
    private val ingest: SensorDataSyncWorker,
    private val bloodPressure: BloodPressureRequestHandler,
    private val results: SnapshotResultLog,
    private val replies: WatchReplySink,
) : SnapshotResultReceiver {

    override suspend fun onSnapshotResult(payload: ByteArray): Boolean {
        val text = String(payload, Charsets.UTF_8)
        val result = runCatching { SnapshotPayloads.decode(text) }
            .onFailure { Log.w(TAG, "Malformed snapshot result: $text", it) }
            .getOrNull() ?: return true
        val stored = result.samples.isEmpty() || ingest.ingest(result.samples)
            .onFailure { Log.w(TAG, "Failed to store the snapshot; watch will retry", it) }
            .isSuccess
        if (!stored) return false
        Log.i(TAG, "Snapshot ${result.status}: ${result.samples.size} sample(s)")
        if (result.status == SnapshotStatus.COMPLETED) bloodPressure.onMeasureRequested(payload)
        results.record(result)
        result.requestId?.let { replies.deliver(it, text) }
        return true
    }

    private companion object {
        const val TAG = "SnapshotResult"
    }
}

/** Asks the watch for a snapshot. */
interface WatchSnapshotRequester {
    /** Sends the command; returns its request id. */
    suspend fun request(): Result<String>

    /** Sends the command and waits for the result; null when none arrived within [timeout]. */
    suspend fun requestAndAwait(timeout: Duration): Result<WatchSnapshotResult?>
}

/** Sends [DataLayerPaths.MESSAGE_CAPTURE_SNAPSHOT] over the watch link and matches the reply by request id. */
class TransportWatchSnapshotRequester(
    private val transport: DeviceLinkTransport,
    private val replies: WatchReplyAwaiter,
) : WatchSnapshotRequester {

    override suspend fun request(): Result<String> {
        val requestId = UUID.randomUUID().toString()
        return send(requestId).map { requestId }
    }

    override suspend fun requestAndAwait(timeout: Duration): Result<WatchSnapshotResult?> {
        val requestId = UUID.randomUUID().toString()
        return replies.await(requestId, timeout) { send(requestId) }.map { reply -> reply?.let(SnapshotPayloads::decode) }
    }

    private suspend fun send(requestId: String): Result<Unit> =
        transport.sendMessage(DataLayerPaths.MESSAGE_CAPTURE_SNAPSHOT, SnapshotPayloads.encodeRequest(requestId))
}
