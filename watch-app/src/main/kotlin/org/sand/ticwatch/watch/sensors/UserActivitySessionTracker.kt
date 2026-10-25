package org.sand.ticwatch.watch.sensors

import android.content.SharedPreferences
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.Duration
import java.time.Instant

/** Side-effect-only listener for Health Services' user-activity state changes (no samples). */
fun interface UserActivityObserver {
    fun onUserActivity(info: UserActivityInfo)
}

/** Turns user-activity state transitions into completed session samples. */
fun interface UserActivitySessionTracker {
    /** Returns a completed session when [info] closes one, otherwise null. */
    fun onUserActivity(info: UserActivityInfo): SensorSample?
}

/**
 * Opens a session on a transition into [trackedState] and closes it on the first
 * transition out of it. The open session (start and [valueAtOpen]) is persisted in
 * [preferences] so a process restart mid-session (the hosting service is START_STICKY)
 * still yields one full session.
 */
class StateSessionTracker(
    private val preferences: SharedPreferences,
    private val trackedState: UserActivityState,
    private val sensorType: SensorType,
    private val minDuration: Duration,
    private val valueAtOpen: (UserActivityInfo) -> Double = { NO_SESSION_VALUE },
) : UserActivitySessionTracker {

    private val startKey = "${sensorType.name}$START_KEY_SUFFIX"
    private val valueKey = "${sensorType.name}$VALUE_KEY_SUFFIX"

    override fun onUserActivity(info: UserActivityInfo): SensorSample? {
        val openStart = preferences.getLong(startKey, NO_OPEN_SESSION)

        if (info.userActivityState == trackedState) {
            if (openStart == NO_OPEN_SESSION) {
                preferences.edit()
                    .putLong(startKey, info.stateChangeTime.toEpochMilli())
                    .putFloat(valueKey, valueAtOpen(info).toFloat())
                    .apply()
            }
            return null
        }
        if (openStart == NO_OPEN_SESSION) return null

        val value = preferences.getFloat(valueKey, NO_SESSION_VALUE.toFloat()).toDouble()
        preferences.edit().remove(startKey).remove(valueKey).apply()
        val start = Instant.ofEpochMilli(openStart)
        if (Duration.between(start, info.stateChangeTime) < minDuration) return null

        return SensorSample(type = sensorType, value = value, recordedAt = start, endedAt = info.stateChangeTime)
    }

    companion object {
        const val PREFERENCES_NAME = "activity_session_state"
        const val NO_SESSION_VALUE = 0.0
        private const val START_KEY_SUFFIX = "_start_epoch_ms"
        private const val VALUE_KEY_SUFFIX = "_value"
        private const val NO_OPEN_SESSION = -1L
    }
}
