package org.sand.ticwatch.phone.health

import java.time.Duration

/**
 * Speed from Health Services' passive distance intervals: no passive speed data type
 * exists. Each interval long enough to be meaningful yields its mean speed, stamped at
 * the interval end; second-long intervals are skipped as too noisy.
 */
class SpeedDeriver(
    private val minInterval: Duration = Duration.ofSeconds(10),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> =
        samples.filter { it.type == SensorType.DISTANCE && it.value > 0 }.mapNotNull { sample ->
            val end = sample.endedAt ?: return@mapNotNull null
            val duration = Duration.between(sample.recordedAt, end)
            if (duration < minInterval) return@mapNotNull null
            SensorSample(SensorType.SPEED, sample.value / duration.toMillis() * MILLIS_PER_SECOND, end)
        }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
