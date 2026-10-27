package org.sand.ticwatch.watch.autoworkout

import java.time.Duration
import java.time.Instant

/**
 * Turns cumulative step counts into "walking briskly since" (cadence over the trailing [window] at
 * [briskCadence] steps/min or more, pauses up to [maxGap] tolerated) and "last moving" (cadence at
 * [movingCadence] or more - slower steps that keep a started workout alive, not enough to start one).
 */
class BriskWalkDetector(
    private val briskCadence: Double,
    private val movingCadence: Double,
    private val window: Duration,
    private val minSpan: Duration,
    private val maxGap: Duration,
) {

    private val counts = ArrayDeque<StepCount>()

    /** Start of the current brisk stretch, null when not walking briskly. */
    var briskSince: Instant? = null
        private set
    private var lastBriskAt: Instant? = null

    /** Last time the wearer moved at [movingCadence] or more; survives the end of a stretch. */
    var lastMovingAt: Instant? = null
        private set

    fun onSteps(count: StepCount) {
        // The counter restarts from zero on reboot.
        if (counts.lastOrNull()?.let { count.total < it.total } == true) counts.clear()
        counts.addLast(count)
        val windowStart = count.at.minus(window)
        while (counts.size > 1 && !counts[1].at.isAfter(windowStart)) counts.removeFirst()

        val anchor = counts.first()
        val span = Duration.between(anchor.at, count.at)
        if (span < minSpan) return
        val cadence = (count.total - anchor.total) * MILLIS_PER_MINUTE / span.toMillis()
        if (cadence >= movingCadence) lastMovingAt = count.at
        if (cadence >= briskCadence) {
            if (briskSince == null) briskSince = anchor.at
            lastBriskAt = count.at
        } else {
            expire(count.at)
        }
    }

    /** Ends the stretch once nothing brisk has been seen for [maxGap] up to [now]. */
    fun expire(now: Instant) {
        val last = lastBriskAt ?: return
        if (Duration.between(last, now) > maxGap) briskSince = null
    }

    /** How long the current stretch has lasted, zero when there is none. */
    fun briskDuration(): Duration {
        val since = briskSince ?: return Duration.ZERO
        return Duration.between(since, lastBriskAt ?: since)
    }

    /** Forgets the stretch, so the next workout needs a fresh one. */
    fun resetStretch() {
        briskSince = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}
