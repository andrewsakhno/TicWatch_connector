package org.sand.ticwatch.watch.datalayer

import android.util.Log
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.sand.ticwatch.watch.sensors.UserActivityObserver
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/**
 * Tells the phone when a Health Services workout starts and ends, so phone-app can record a GPS
 * route for it (the EXERCISE session sample itself is only sent once the workout is over).
 * Only transitions are sent, and the latest state wins: a send still retrying when the state
 * flips again is abandoned, so a late "started" can never overtake an "ended".
 */
class WorkoutStateNotifier(
    private val transport: DeviceLinkTransport,
    scope: CoroutineScope,
) : UserActivityObserver {

    private val active = MutableStateFlow<Boolean?>(null)

    init {
        scope.launch {
            active.filterNotNull().collectLatest { isActive -> sendWithRetry(isActive) }
        }
    }

    override fun onUserActivity(info: UserActivityInfo) {
        val isActive = info.userActivityState == UserActivityState.USER_ACTIVITY_EXERCISE
        // The first non-exercise state after start-up is not an "ended": nothing was started.
        if (active.value == null && !isActive) return
        active.value = isActive
    }

    private suspend fun sendWithRetry(isActive: Boolean) {
        val payload = JSONObject().put(ACTIVE, isActive).toString().toByteArray(Charsets.UTF_8)
        repeat(MAX_ATTEMPTS) { attempt ->
            if (transport.sendMessage(DataLayerPaths.MESSAGE_WORKOUT_STATE, payload).isSuccess) {
                Log.i(TAG, "Sent workout state active=$isActive")
                return
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        Log.w(TAG, "Gave up sending workout state active=$isActive after $MAX_ATTEMPTS attempts")
    }

    private companion object {
        const val TAG = "WorkoutStateNotifier"

        /** Wire key shared with phone-app's `WorkoutPayloads.ACTIVE`. */
        const val ACTIVE = "active"
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 10_000L
    }
}
