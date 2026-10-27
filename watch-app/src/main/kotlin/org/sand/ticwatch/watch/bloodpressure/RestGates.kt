package org.sand.ticwatch.watch.bloodpressure

import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import org.sand.ticwatch.watch.autoworkout.StepCount
import org.sand.ticwatch.watch.autoworkout.StepCountSource
import org.sand.ticwatch.watch.sensors.UserActivityObserver
import org.sand.ticwatch.watch.sensors.WindowGate
import java.time.Clock
import java.time.Duration

/**
 * Open while the step counter reported at most [maxSteps] over the last [window]. Keeps the counts of that window
 * plus the last one before it as the baseline; a counter reset (reboot) counts from zero.
 */
class RecentStepsGate(
    private val steps: StepCountSource,
    private val window: Duration,
    private val maxSteps: Long,
    private val clock: Clock,
) : WindowGate {

    private val counts = ArrayDeque<StepCount>()

    /** Collects the step counter until cancelled. */
    suspend fun track() = steps.steps().collect(::record)

    override fun isOpen(): Boolean = stepsSince(clock.instant().minus(window)) <= maxSteps

    private fun record(count: StepCount) = synchronized(counts) {
        counts.addLast(count)
        val windowStart = count.at.minus(window)
        while (counts.size > 1 && !counts[1].at.isAfter(windowStart)) counts.removeFirst()
    }

    private fun stepsSince(from: java.time.Instant): Long = synchronized(counts) {
        val latest = counts.lastOrNull() ?: return 0
        val baseline = counts.lastOrNull { !it.at.isAfter(from) } ?: counts.first()
        val walked = latest.total - baseline.total
        if (walked < 0) latest.total else walked
    }
}

/** Closed while any app's Health Services workout runs: exercise isn't rest, even without steps (cycling). */
class NoWorkoutGate : WindowGate, UserActivityObserver {

    @Volatile
    private var exercising = false

    override fun onUserActivity(info: UserActivityInfo) {
        exercising = info.userActivityState == UserActivityState.USER_ACTIVITY_EXERCISE
    }

    override fun isOpen(): Boolean = !exercising
}
