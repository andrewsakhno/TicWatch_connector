package org.sand.ticwatch.phone.snapshot

import android.util.Log
import androidx.annotation.StringRes
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.datalayer.BloodPressureRequestHandler
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.WatchReplyAwaiter
import org.sand.ticwatch.phone.datalayer.WatchReplySink
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.health.SensorSample
import org.sand.ticwatch.phone.health.SensorSampleJson
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage

/** Outcome of a watch snapshot; wire contract with watch-app's `SnapshotStatus` (matched by name). */
enum class SnapshotStatus {
    COMPLETED,
    NOT_WORN,
    NOT_STILL,
    NO_READINGS,
    BUSY,
}

/**
 * What a running snapshot is doing now, in execution order. [CONNECTING], [COMMAND_ACCEPTED] and [SAVING] happen on
 * the phone; the others are a wire contract with watch-app's `SnapshotStage` (matched by name).
 */
enum class SnapshotStage(@StringRes val label: Int) {
    CONNECTING(R.string.snapshot_stage_connecting),
    COMMAND_ACCEPTED(R.string.snapshot_stage_command_accepted),
    STILLNESS_CHECK(R.string.snapshot_stage_stillness),
    MEASURING(R.string.snapshot_stage_measuring),
    SYNCING(R.string.snapshot_stage_syncing),
    SENDING_RESULT(R.string.snapshot_stage_sending_result),
    SAVING(R.string.snapshot_stage_saving),
}

/** The metrics a measuring watch polls, all at once, and those whose sensors already [answering]. */
data class SnapshotMetrics(val polled: Set<SensorType> = emptySet(), val answering: Set<SensorType> = emptySet())

/** The stage a running snapshot is in and since when, so a stuck one shows on screen. */
data class SnapshotProgress(val stage: SnapshotStage, val since: Instant, val metrics: SnapshotMetrics = SnapshotMetrics())

/** One `/snapshot_progress` message. */
data class SnapshotProgressUpdate(val stage: SnapshotStage, val metrics: SnapshotMetrics)

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
    const val STAGE = "stage"
    const val POLLED = "polled"
    const val ANSWERING = "answering"

    fun decodeProgress(text: String): SnapshotProgressUpdate {
        val json = JSONObject(text)
        return SnapshotProgressUpdate(
            stage = SnapshotStage.valueOf(json.getString(STAGE)),
            metrics = SnapshotMetrics(polled = decodeMetrics(json, POLLED), answering = decodeMetrics(json, ANSWERING)),
        )
    }

    /** A metric this phone doesn't know yet is left out rather than failing the whole message. */
    private fun decodeMetrics(json: JSONObject, key: String): Set<SensorType> {
        val names = json.optJSONArray(key) ?: return emptySet()
        return (0 until names.length()).mapNotNull { index -> SensorType.entries.firstOrNull { it.name == names.getString(index) } }.toSet()
    }

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
    val isRunning: StateFlow<Boolean>
    val progress: StateFlow<SnapshotProgress>
    val error: StateFlow<UiText?>
    fun record(result: WatchSnapshotResult)

    /** Moves the running snapshot on to [stage]; ignored when none runs or it is already past it (stages can arrive late). */
    fun advanceTo(stage: SnapshotStage, metrics: SnapshotMetrics = SnapshotMetrics())
    fun requestSnapshot(requester: WatchSnapshotRequester, timeout: Duration)
}

/** Process-wide: results arrive in whichever link service the transport woke, while the screen lives in the activity. */
object ProcessSnapshotResults : SnapshotResultLog {
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow<WatchSnapshotResult?>(null)
    private val running = MutableStateFlow(false)
    private val currentProgress = MutableStateFlow(SnapshotProgress(SnapshotStage.CONNECTING, Instant.EPOCH))
    private val lastError = MutableStateFlow<UiText?>(null)

    override val latest: StateFlow<WatchSnapshotResult?> = current.asStateFlow()
    override val isRunning: StateFlow<Boolean> = running.asStateFlow()
    override val progress: StateFlow<SnapshotProgress> = currentProgress.asStateFlow()
    override val error: StateFlow<UiText?> = lastError.asStateFlow()

    override fun record(result: WatchSnapshotResult) {
        current.value = result
        running.value = false
        // A result arriving after the deadline replaces the "stuck" message.
        lastError.value = null
    }

    override fun advanceTo(stage: SnapshotStage, metrics: SnapshotMetrics) {
        val now = currentProgress.value
        if (!running.value || stage < now.stage) return
        // The same stage again only updates its metrics: the elapsed time keeps counting.
        currentProgress.value = if (stage == now.stage) now.copy(metrics = metrics) else SnapshotProgress(stage, Instant.now(), metrics)
    }

    override fun requestSnapshot(requester: WatchSnapshotRequester, timeout: Duration) {
        if (running.value) return
        running.value = true
        currentProgress.value = SnapshotProgress(SnapshotStage.CONNECTING, Instant.now())
        lastError.value = null
        scope.launch {
            // Abandoned rather than cancelled in place: a send blocked on the link's ACK ignores cancellation.
            val call = scope.async { requester.requestAndAwait(timeout) { advanceTo(SnapshotStage.COMMAND_ACCEPTED) } }
            val outcome = withTimeoutOrNull(timeout + COMMAND_SEND_GRACE) { call.await() }
                ?: Result.success(null).also { call.cancel() }
            outcome
                .onSuccess { result ->
                    // Recorded just before the reply came due: only a still running snapshot is stuck.
                    if (result == null && running.value) {
                        val (stage, since) = currentProgress.value
                        Log.w(TAG, "No snapshot result, stuck at $stage since $since")
                        lastError.value = uiText(R.string.snapshot_stuck, uiText(stage.label))
                    }
                }
                .onFailure { lastError.value = uiText(R.string.watch_unreachable, it.message.orEmpty()) }
            running.value = false
        }
    }

    internal fun resetForTest(testScope: CoroutineScope? = null) {
        testScope?.let { scope = it }
        current.value = null
        running.value = false
        lastError.value = null
    }

    private const val TAG = "SnapshotResults"
    private val COMMAND_SEND_GRACE = 1.minutes
}

/** Handles the watch's snapshot messages. */
interface SnapshotResultReceiver {
    /** `/snapshot_result`; false when its samples weren't stored, so the watch keeps them. */
    suspend fun onSnapshotResult(payload: ByteArray): Boolean

    /** `/snapshot_progress`. */
    fun onSnapshotProgress(payload: ByteArray)
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
        results.advanceTo(SnapshotStage.SAVING)
        val stored = result.samples.isEmpty() || ingest.ingest(result.samples)
            .onFailure { Log.w(TAG, "Failed to store the snapshot; watch will retry", it) }
            .isSuccess
        if (!stored) return false
        Log.i(TAG, "Snapshot ${result.status}: ${result.samples.size} sample(s)")
        // Before the estimate: its answer to the watch may block on the link, and the screen doesn't depend on it.
        results.record(result)
        if (result.status == SnapshotStatus.COMPLETED) bloodPressure.onMeasureRequested(payload)
        result.requestId?.let { replies.deliver(it, text) }
        return true
    }

    override fun onSnapshotProgress(payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        runCatching { SnapshotPayloads.decodeProgress(text) }
            .onSuccess { update ->
                Log.i(TAG, "Snapshot progress $update")
                results.advanceTo(update.stage, update.metrics)
            }
            .onFailure { Log.w(TAG, "Malformed snapshot progress: $text", it) }
    }

    private companion object {
        const val TAG = "SnapshotResult"
    }
}

/** Asks the watch for a snapshot. */
interface WatchSnapshotRequester {
    /** Sends the command; returns its request id. */
    suspend fun request(): Result<String>

    /** Sends the command and waits for the result; null when none arrived within [timeout]. [onAccepted] hears the watch's ACK of the command. */
    suspend fun requestAndAwait(timeout: Duration, onAccepted: () -> Unit = {}): Result<WatchSnapshotResult?>
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

    override suspend fun requestAndAwait(timeout: Duration, onAccepted: () -> Unit): Result<WatchSnapshotResult?> {
        val requestId = UUID.randomUUID().toString()
        return replies.await(requestId, timeout) { send(requestId).onSuccess { onAccepted() } }.map { reply -> reply?.let(SnapshotPayloads::decode) }
    }

    private suspend fun send(requestId: String): Result<Unit> =
        transport.sendMessage(DataLayerPaths.MESSAGE_CAPTURE_SNAPSHOT, SnapshotPayloads.encodeRequest(requestId))
}
