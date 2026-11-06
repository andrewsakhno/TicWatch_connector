package org.sand.ticwatch.phone.route

import android.content.Context
import android.content.SharedPreferences
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether a workout runs on the watch, as last reported, and since when (phone clock). */
data class WatchWorkoutActivity(val active: Boolean, val since: Instant) {
    /** An "active" older than [MAX_ACTIVE] lost its end message (link down): treated as over. */
    fun isRunning(now: Instant): Boolean = active && Duration.between(since, now) < MAX_ACTIVE

    companion object {
        /** Same bound as the phone's route tracking self-stop. */
        val MAX_ACTIVE: Duration = Duration.ofHours(6)
    }
}

/** Last known watch workout state; the Metrics card observes [activity]. */
interface WatchWorkoutActivityStore {
    val activity: StateFlow<WatchWorkoutActivity?>
    fun markActive(active: Boolean)
}

/**
 * [WatchWorkoutActivityStore] in SharedPreferences, one per process, so the activity's card sees what the link
 * service's router stored. A repeated "active" keeps its original start. First access reads the file - off the main thread.
 */
class SharedPreferencesWatchWorkoutActivityStore private constructor(
    private val prefs: SharedPreferences,
    private val clock: Clock,
) : WatchWorkoutActivityStore {

    private val state = MutableStateFlow(read())
    override val activity: StateFlow<WatchWorkoutActivity?> = state

    @Synchronized
    override fun markActive(active: Boolean) {
        val now = clock.instant()
        val previous = state.value
        if (previous != null && previous.active == active && (!active || previous.isRunning(now))) return
        val next = WatchWorkoutActivity(active, now)
        prefs.edit().putBoolean(KEY_ACTIVE, next.active).putLong(KEY_SINCE, next.since.toEpochMilli()).apply()
        state.value = next
    }

    private fun read(): WatchWorkoutActivity? {
        if (!prefs.contains(KEY_ACTIVE)) return null
        return WatchWorkoutActivity(prefs.getBoolean(KEY_ACTIVE, false), Instant.ofEpochMilli(prefs.getLong(KEY_SINCE, 0L)))
    }

    companion object {
        private const val PREFS_NAME = "watch_workout_state"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SINCE = "since_epoch_ms"

        @Volatile private var instance: SharedPreferencesWatchWorkoutActivityStore? = null

        fun get(context: Context): SharedPreferencesWatchWorkoutActivityStore = instance ?: synchronized(this) {
            instance ?: SharedPreferencesWatchWorkoutActivityStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                Clock.systemUTC(),
            ).also { instance = it }
        }
    }
}

/** Decorator recording the watch's workout transitions in [store] before [delegate] reacts to them. */
class StateTrackingWorkoutRouteRecorder(
    private val delegate: WorkoutRouteRecorder,
    private val store: WatchWorkoutActivityStore,
) : WorkoutRouteRecorder {
    override fun onWorkoutStarted() {
        store.markActive(true)
        delegate.onWorkoutStarted()
    }

    override fun onWorkoutEnded() {
        store.markActive(false)
        delegate.onWorkoutEnded()
    }
}
