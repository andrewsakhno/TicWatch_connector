package org.sand.ticwatch.watch.datalayer

import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.CoroutineScope
import org.sand.ticwatch.watch.route.WorkoutPayloads
import org.sand.ticwatch.watch.sensors.UserActivityObserver
import org.sand.ticwatch.watch.transport.DeviceLinkTransport

/**
 * Tells the phone when a Health Services workout starts and ends, so phone-app can record a GPS
 * route for it (the EXERCISE session sample itself is only sent once the workout is over).
 * Only transitions are sent, the latest state winning ([LatestMessageSender]).
 */
class WorkoutStateNotifier(transport: DeviceLinkTransport, scope: CoroutineScope) : UserActivityObserver {

    private val sender = LatestMessageSender(transport, scope, DataLayerPaths.MESSAGE_WORKOUT_STATE, WorkoutPayloads::workoutState)

    override fun onUserActivity(info: UserActivityInfo) {
        val isActive = info.userActivityState == UserActivityState.USER_ACTIVITY_EXERCISE
        // The first non-exercise state after start-up is not an "ended": nothing was started.
        if (sender.current == null && !isActive) return
        sender.update(isActive)
    }
}
