package org.sand.ticwatch.phone.health

import java.time.Duration

/**
 * Step cadence (steps/min) from Health Services' passive step intervals: no passive cadence data
 * type exists. Each interval of [minInterval]..[maxInterval] yields its mean cadence, stamped at the
 * interval end; shorter ones are too noisy, longer ones average walking with standing still. Only
 * cadences of at least [minWalkingCadence] count as walking/running - lower ones are shuffling
 * around, not a gait.
 */
class StepsCadenceDeriver(
    private val minInterval: Duration = Duration.ofSeconds(30),
    private val maxInterval: Duration = Duration.ofMinutes(10),
    private val minWalkingCadence: Double = 60.0,
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> =
        samples.filter { it.type == SensorType.STEPS && it.value > 0 }.mapNotNull { sample ->
            val end = sample.endedAt ?: return@mapNotNull null
            val duration = Duration.between(sample.recordedAt, end)
            if (duration < minInterval || duration > maxInterval) return@mapNotNull null
            val cadence = sample.value / duration.toMillis() * MILLIS_PER_MINUTE
            if (cadence < minWalkingCadence) return@mapNotNull null
            SensorSample(SensorType.STEPS_CADENCE, cadence, end)
        }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000.0
    }
}
