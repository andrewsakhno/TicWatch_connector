package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Duration
import java.time.Instant

/** Whether the wearer is inside a sleep session right now. */
fun interface SleepState {
    fun isAsleep(): Boolean
}

/** Write side of [SleepState], fed by the sleep-stage source on every sleep-sensor event. */
fun interface SleepStateSink {
    fun update(inSession: Boolean, at: Instant)
}

/**
 * [SleepState] from the last sleep-sensor event. The sensor only reports changes, so a session stays
 * open between events - but no longer than [maxSilence] after the last one, the same gap that makes
 * [SleepStageSegmenter] end the night (watch off, sensor stopped).
 */
class TrackedSleepState(
    private val maxSilence: Duration,
    private val clock: () -> Instant = Instant::now,
) : SleepState, SleepStateSink {

    private val session = MutableStateFlow(false)

    /** Whether the sensor reports a session open (emits on transitions, unlike the silence limit of [isAsleep]). */
    val sessionChanges: StateFlow<Boolean> = session

    @Volatile
    private var lastEventAt: Instant? = null

    override fun update(inSession: Boolean, at: Instant) {
        session.value = inSession
        lastEventAt = at
    }

    override fun isAsleep(): Boolean {
        val last = lastEventAt ?: return false
        return session.value && Duration.between(last, clock()) <= maxSilence
    }
}
