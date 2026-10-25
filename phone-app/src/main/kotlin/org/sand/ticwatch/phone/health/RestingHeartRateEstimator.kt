package org.sand.ticwatch.phone.health

import java.time.Duration

/**
 * Derives a resting-heart-rate estimate from the live heart-rate stream. Health Services
 * on the watch has no resting-HR data type of its own (only continuous BPM), so this
 * approximates it as the lowest BPM observed within a trailing window, without needing
 * activity-state classification.
 */
class RestingHeartRateEstimator(private val window: Duration = Duration.ofMinutes(10)) {

    private val recent = ArrayDeque<SensorSample>()

    fun onHeartRateSample(sample: SensorSample): SensorSample {
        recent.addLast(sample)
        val cutoff = sample.recordedAt.minus(window)
        while (recent.first().recordedAt.isBefore(cutoff)) {
            recent.removeFirst()
        }
        val restingBpm = recent.minOf { it.value }
        return SensorSample(SensorType.RESTING_HEART_RATE, restingBpm, sample.recordedAt)
    }
}
