package org.sand.ticwatch.phone.datalayer

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/** What a workout command did on the watch, mirrored by watch-app's `WorkoutCommandOutcome`. */
/** [runningAfter]: whether the watch's workout runs once the command was handled; null = unknown. */
enum class WatchWorkoutOutcome(@StringRes val label: Int, val refused: Boolean = false, val runningAfter: Boolean? = null) {
    STARTED(R.string.workout_status_started, runningAfter = true),
    ALREADY_RUNNING(R.string.workout_status_already_running, runningAfter = true),
    OTHER_APP_RUNNING(R.string.workout_status_other_app_running, refused = true),
    ENDED(R.string.workout_status_ended, runningAfter = false),
    NOT_RUNNING(R.string.workout_status_not_running, runningAfter = false),
    FAILED(R.string.workout_status_failed, refused = true),
}

/** `/command/workout/result`, mirrored by watch-app's `WorkoutCommandPayloads`. */
object WatchWorkoutPayloads {
    private const val STATUS = "status"

    fun decodeOutcome(reply: String): WatchWorkoutOutcome = WatchWorkoutOutcome.valueOf(JSONObject(reply).getString(STATUS))
}

/** Starts and ends the watch's workout. A failure means no answer came, so the command may still have run. */
interface WatchWorkoutCommands {
    suspend fun startWatchWorkout(): Result<WatchWorkoutOutcome>

    suspend fun endWatchWorkout(): Result<WatchWorkoutOutcome>
}

/** Sends the workout commands over the watch link and waits up to [timeout] for the watch's answer. */
class TransportWatchWorkoutCommands(
    private val transport: DeviceLinkTransport,
    private val replies: WatchReplyAwaiter,
    private val scope: CoroutineScope,
    private val timeout: Duration,
) : WatchWorkoutCommands {
    override suspend fun startWatchWorkout(): Result<WatchWorkoutOutcome> = send(DataLayerPaths.MESSAGE_START_WORKOUT)

    override suspend fun endWatchWorkout(): Result<WatchWorkoutOutcome> = send(DataLayerPaths.MESSAGE_END_WORKOUT)

    private suspend fun send(path: String): Result<WatchWorkoutOutcome> {
        val requestId = UUID.randomUUID().toString()
        // Abandoned rather than cancelled in place: a send blocked on the link's ACK ignores cancellation.
        val call = scope.async {
            replies.await(requestId, timeout) { transport.sendMessage(path, SyncNowPayloads.encodeRequest(requestId)) }
        }
        val reply = withTimeoutOrNull(timeout) { call.await() }?.getOrElse { return Result.failure(it) }
        if (reply == null) {
            call.cancel()
            return Result.failure(TimeoutException("No answer from the watch within $timeout"))
        }
        return runCatching { WatchWorkoutPayloads.decodeOutcome(reply) }
    }
}
