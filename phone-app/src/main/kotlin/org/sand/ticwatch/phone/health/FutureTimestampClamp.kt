package org.sand.ticwatch.phone.health

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Pulls samples stamped ahead of the phone's clock back to its "now". The watch's clock can run a second or two fast,
 * and Health Connect rejects a record that starts or ends in the future - which failed that type's whole batch, so the
 * watch re-sent the push. A sample is shifted as a whole by how far its end (an instant's time) is ahead, so an interval
 * keeps its length and stays start < end. One `now` per call, so samples stamped at the same instant (the two halves of
 * a blood pressure reading, looked up by exact time) keep sharing it.
 */
class FutureTimestampClamp(private val clock: Clock) {

    fun clamp(samples: List<SensorSample>): List<SensorSample> {
        val now = clock.instant()
        return samples.map { it.shiftedBefore(now) }
    }

    private fun SensorSample.shiftedBefore(now: Instant): SensorSample {
        val ahead = Duration.between(now, endedAt ?: recordedAt)
        if (ahead.isNegative || ahead.isZero) return this
        return copy(recordedAt = recordedAt.minus(ahead), endedAt = endedAt?.minus(ahead))
    }
}
