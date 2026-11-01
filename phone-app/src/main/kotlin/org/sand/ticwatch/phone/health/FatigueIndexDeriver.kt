package org.sand.ticwatch.phone.health

import org.sand.ticwatch.phone.insights.FatigueIndexSource
import java.time.Clock
import java.time.Duration

/**
 * One [SensorType.FATIGUE_INDEX] point (percent, history only) at most every [minInterval], however often the watch
 * pushes - live sync pushes every few seconds. Must run after the sleep score and cardio load derivers, whose
 * results for this push it reads. [log] holds the last stored point; a push without enough data records nothing.
 */
class FatigueIndexDeriver(
    private val source: FatigueIndexSource,
    private val log: InstantLog,
    private val clock: Clock,
    private val minInterval: Duration = Duration.ofMinutes(30),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val now = clock.instant()
        val last = log.last()
        if (last != null && Duration.between(last, now) < minInterval) return emptyList()
        val index = source.current(samples) ?: return emptyList()
        log.record(now)
        return listOf(SensorSample(SensorType.FATIGUE_INDEX, index.percent.toDouble(), now))
    }
}
