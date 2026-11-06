package org.sand.ticwatch.watch.autoworkout

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.SyncNowPayloads
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/** What a workout start/end command did, mirrored by phone-app's `WatchWorkoutOutcome`. */
enum class WorkoutCommandOutcome {
    STARTED,
    ALREADY_RUNNING,
    OTHER_APP_RUNNING,
    ENDED,
    NOT_RUNNING,
    FAILED,
}

/** `/command/workout/result`, mirrored by phone-app's `WatchWorkoutPayloads`. */
object WorkoutCommandPayloads {
    const val STATUS = "status"

    fun encodeResult(requestId: String, outcome: WorkoutCommandOutcome): ByteArray = JSONObject()
        .put(SyncNowPayloads.REQUEST_ID, requestId)
        .put(STATUS, outcome.name)
        .toString()
        .toByteArray(Charsets.UTF_8)
}

/** Runs a workout command and answers the phone with its outcome; the notification's buttons have no [requestId] to answer. */
class WorkoutCommandResponder(
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
) {
    fun respond(requestId: String?, command: suspend () -> WorkoutCommandOutcome) {
        scope.launch {
            val outcome = runCatching { command() }
                .onFailure { Log.w(TAG, "Workout command failed", it) }
                .getOrDefault(WorkoutCommandOutcome.FAILED)
            Log.i(TAG, "Workout command ($requestId): $outcome")
            if (requestId == null) return@launch
            transport.sendMessage(DataLayerPaths.MESSAGE_WORKOUT_COMMAND_RESULT, WorkoutCommandPayloads.encodeResult(requestId, outcome))
                .onFailure { Log.w(TAG, "Workout command result not delivered", it) }
        }
    }

    private companion object {
        const val TAG = "WorkoutCommandResponder"
    }
}
