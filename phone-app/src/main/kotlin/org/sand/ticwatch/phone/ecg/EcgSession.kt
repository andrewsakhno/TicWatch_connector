package org.sand.ticwatch.phone.ecg

import android.content.Context
import android.util.Log
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.WatchReplyAwaiter
import org.sand.ticwatch.phone.datalayer.WatchReplySink
import org.sand.ticwatch.phone.cloud.CloudModule
import org.sand.ticwatch.phone.history.HistorySettingKeys
import org.sand.ticwatch.phone.history.SettingStore
import org.sand.ticwatch.phone.history.SharedPreferencesEnumSettingStore
import org.sand.ticwatch.phone.medication.CardioDatabase
import org.sand.ticwatch.phone.text.LiteralText
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage

enum class EcgSessionPhase { RECORDING, FINISHED, FAILED }

/** The recording in progress (or just ended): beats so far, drawn live; [recordingId] once stored. */
data class LiveEcgSession(
    val requestId: String,
    val requestedAt: Instant,
    val phase: EcgSessionPhase,
    val beats: List<EcgBeat> = emptyList(),
    val wave: List<EcgWaveSample> = emptyList(),
    val message: UiText? = null,
    val recordingId: Long? = null,
)

/** Process-wide live session: progress and result land in whichever link service the transport woke. */
interface EcgSessionLog {
    val live: StateFlow<LiveEcgSession?>
    fun start(requester: EcgRequester, timeout: Duration)
    fun onProgress(progress: EcgProgress)
    fun onResult(result: EcgCaptureResult, recordingId: Long?)
}

object ProcessEcgSessions : EcgSessionLog {
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow<LiveEcgSession?>(null)
    override val live: StateFlow<LiveEcgSession?> = current.asStateFlow()

    override fun start(requester: EcgRequester, timeout: Duration) {
        if (current.value?.phase == EcgSessionPhase.RECORDING) return
        val requestId = UUID.randomUUID().toString()
        current.value = LiveEcgSession(requestId, Instant.now(), EcgSessionPhase.RECORDING)
        scope.launch {
            val outcome = requester.requestAndAwait(requestId, timeout)
            val failure = outcome.fold(
                onSuccess = { answered -> uiText(R.string.no_answer_from_watch).takeIf { !answered } },
                onFailure = { uiText(R.string.watch_unreachable, it.message.orEmpty()) },
            ) ?: return@launch
            current.update { session ->
                session?.takeIf { it.requestId == requestId && it.phase == EcgSessionPhase.RECORDING }
                    ?.copy(phase = EcgSessionPhase.FAILED, message = failure) ?: session
            }
        }
    }

    override fun onProgress(progress: EcgProgress) = current.update { session ->
        if (session == null || session.requestId != progress.requestId || session.phase != EcgSessionPhase.RECORDING) session
        else session.copy(
            beats = (session.beats + progress.beats).distinctBy { it.offsetMs },
            wave = (session.wave + progress.wave).distinctBy { it.offsetMs },
        )
    }

    override fun onResult(result: EcgCaptureResult, recordingId: Long?) = current.update { session ->
        if (session == null || session.requestId != result.requestId) session
        else session.copy(
            phase = if (result.status == EcgCaptureStatus.COMPLETED) EcgSessionPhase.FINISHED else EcgSessionPhase.FAILED,
            beats = result.recording.beats,
            wave = result.recording.wave,
            message = result.message?.let(::LiteralText),
            recordingId = recordingId,
        )
    }
}

/** Handles the watch's `/ecg/progress` and `/ecg/result`. */
interface EcgMessageReceiver {
    fun onProgress(payload: ByteArray)

    /** False when a completed strip couldn't be stored. */
    suspend fun onResult(payload: ByteArray): Boolean

    /** A queued night pulse strip: stored like a result, without a session or a waiter; false when it couldn't be stored. */
    suspend fun onNightStrip(payload: ByteArray): Boolean
}

/** Stores completed strips in [log] (then runs [storedListeners]), keeps the [sessions] screen state current and answers the request's waiter. */
class StoringEcgMessageReceiver(
    private val log: EcgRecordingLog,
    private val sessions: EcgSessionLog,
    private val replies: WatchReplySink,
    /** Run after a strip is stored (the pulse blood pressure calibration). */
    private val storedListeners: List<EcgRecordingStoredListener>,
) : EcgMessageReceiver {

    override fun onProgress(payload: ByteArray) {
        runCatching { EcgPayloads.decodeProgress(String(payload, Charsets.UTF_8)) }
            .onSuccess(sessions::onProgress)
            .onFailure { Log.w(TAG, "Malformed rhythm progress", it) }
    }

    override suspend fun onResult(payload: ByteArray): Boolean {
        val text = String(payload, Charsets.UTF_8)
        val result = runCatching { EcgPayloads.decodeResult(text) }
            .onFailure { Log.w(TAG, "Malformed rhythm result: $text", it) }
            .getOrNull() ?: return true
        val recordingId = if (result.status == EcgCaptureStatus.COMPLETED) {
            runCatching { log.save(result.recording) }.onFailure { Log.w(TAG, "Failed to store the rhythm strip", it) }.getOrNull()
                ?: return false
        } else {
            null
        }
        Log.i(TAG, "Rhythm strip ${result.status}: ${result.recording.beats.size} beat(s), stored as $recordingId")
        sessions.onResult(result, recordingId)
        result.requestId?.let { replies.deliver(it, text) }
        recordingId?.let { notifyStored(result.recording.copy(id = it)) }
        return true
    }

    override suspend fun onNightStrip(payload: ByteArray): Boolean {
        val text = String(payload, Charsets.UTF_8)
        // A malformed or incomplete strip is acknowledged anyway: re-sending it would never help.
        val result = runCatching { EcgPayloads.decodeResult(text) }
            .onFailure { Log.w(TAG, "Malformed night strip", it) }
            .getOrNull()?.takeIf { it.status == EcgCaptureStatus.COMPLETED } ?: return true
        val recording = result.recording.copy(kind = EcgRecordingKind.NIGHT_PULSE)
        val id = runCatching { log.save(recording) }.onFailure { Log.w(TAG, "Failed to store the night strip", it) }.getOrNull()
            ?: return false
        Log.i(TAG, "Night pulse strip at ${recording.startedAt}: ${recording.beats.size} beat(s), stored as $id")
        notifyStored(recording.copy(id = id))
        return true
    }

    private suspend fun notifyStored(recording: EcgRecording) = storedListeners.forEach { listener ->
        runCatching { listener.onStored(recording) }.onFailure { Log.w(TAG, "Post-store step failed", it) }
    }

    private companion object {
        const val TAG = "EcgReceiver"
    }
}

/** Asks the watch to record a rhythm strip. */
fun interface EcgRequester {
    /** Sends the command and waits for the result; true when one arrived within [timeout]. */
    suspend fun requestAndAwait(requestId: String, timeout: Duration): Result<Boolean>
}

/** Sends the command on [path] over the watch link and waits for the reply by request id. */
class TransportEcgRequester(
    private val transport: DeviceLinkTransport,
    private val replies: WatchReplyAwaiter,
    private val path: String = DataLayerPaths.MESSAGE_ECG_CAPTURE,
) : EcgRequester {
    override suspend fun requestAndAwait(requestId: String, timeout: Duration): Result<Boolean> =
        replies.await(requestId, timeout) { transport.sendMessage(path, EcgPayloads.encodeRequest(requestId)) }
            .map { it != null }
}

/** Composition of the cardiogram retention, shared by `AppContainer` and the daily purge worker. */
object EcgModule {
    fun retentionStore(context: Context): SettingStore<EcgRetention> = SharedPreferencesEnumSettingStore(
        context, HistorySettingKeys.ECG_RETENTION, EcgRetention.entries, EcgRetention.DEFAULT,
    )

    fun log(context: Context): RoomEcgRecordingLog = RoomEcgRecordingLog(CardioDatabase.getInstance(context).ecgRecordings())

    fun purger(context: Context): EcgRecordingPurger =
        EcgRecordingPurger(log(context), retentionStore(context), CloudModule.retentionHold(context), Clock.systemUTC())
}
