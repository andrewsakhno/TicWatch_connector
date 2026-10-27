package org.sand.ticwatch.phone.health

import java.time.Duration
import java.time.Instant

/** One completed sleep session of a push: [start] to [end]. */
private class Night(val start: Instant, val end: Instant)

/** Completed sleep sessions carried by [samples]; the watch sends a session once the night has ended. */
private fun nights(samples: List<SensorSample>): List<Night> =
    samples.filter { it.type == SensorType.SLEEP }.mapNotNull { sample -> sample.endedAt?.let { Night(sample.recordedAt, it) } }

private fun Duration.toFractionalHours(): Double = toMillis() / MILLIS_PER_HOUR
private fun Duration.toFractionalMinutes(): Double = toMillis() / MILLIS_PER_MINUTE
private const val MILLIS_PER_HOUR = 3_600_000.0
private const val MILLIS_PER_MINUTE = 60_000.0

/**
 * Per-night breathing summary from the watch's night monitor, history only (Health Connect has no
 * record for either): [SensorType.OXYGEN_DESATURATION_INDEX], desaturations per hour of SpO2-monitored
 * sleep (the night's SpO2 epochs; nothing below [minMonitored]), and [SensorType.SNORING_DURATION],
 * each epoch's snoring share times its length. The ODI is the home screening counterpart of the apnea-
 * hypopnea index - a wrist PPG estimate, not a diagnosis.
 */
class SleepBreathingSummaryDeriver(
    private val reader: SampleWindowReader,
    private val minMonitored: Duration = Duration.ofHours(1),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> = nights(samples).flatMap { night ->
        val nightSamples = reader.samples(NIGHT_TYPES, night.start, night.end, samples)
        listOfNotNull(desaturationIndex(night, nightSamples), snoringDuration(night, nightSamples))
    }

    private fun desaturationIndex(night: Night, samples: List<SensorSample>): SensorSample? {
        val monitored = samples.filter { it.type == SensorType.SPO2 }
            .mapNotNull { epoch -> epoch.endedAt?.let { Duration.between(epoch.recordedAt, it) } }
            .fold(Duration.ZERO, Duration::plus)
        if (monitored < minMonitored) return null
        val events = samples.count { it.type == SensorType.OXYGEN_DESATURATION }
        return SensorSample(SensorType.OXYGEN_DESATURATION_INDEX, events / monitored.toFractionalHours(), night.start, night.end)
    }

    private fun snoringDuration(night: Night, samples: List<SensorSample>): SensorSample? {
        val epochs = samples.filter { it.type == SensorType.SNORING && it.endedAt != null }
        if (epochs.isEmpty()) return null
        val minutes = epochs.sumOf { it.value * Duration.between(it.recordedAt, it.endedAt).toFractionalMinutes() }
        return SensorSample(SensorType.SNORING_DURATION, minutes, night.start, night.end)
    }

    private companion object {
        val NIGHT_TYPES = setOf(SensorType.SPO2, SensorType.OXYGEN_DESATURATION, SensorType.SNORING)
    }
}

/**
 * Health Connect's skin temperature is a nightly deviation from a personal baseline (what Google Health
 * charts), so each night becomes one [SensorType.SKIN_TEMPERATURE_NIGHTLY] sample spanning the session
 * whose value is the baseline from [baselines]; the mapper adds the night's readings as deltas. Nothing
 * until enough earlier nights exist for a baseline, or for a night without skin readings.
 */
class NightlySkinTemperatureDeriver(
    private val reader: SampleWindowReader,
    private val baselines: SkinBaselineSource,
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> = nights(samples).mapNotNull { night ->
        if (reader.samples(SKIN, night.start, night.end, samples).isEmpty()) return@mapNotNull null
        val baseline = baselines.nightlyBaseline(night.start) ?: return@mapNotNull null
        SensorSample(SensorType.SKIN_TEMPERATURE_NIGHTLY, baseline, night.start, night.end)
    }

    private companion object {
        val SKIN = setOf(SensorType.SKIN_TEMPERATURE)
    }
}

/**
 * Basal body temperature: the lowest settled body-temperature estimate of a night, i.e. the lowest
 * median of [smoothing] consecutive estimates (one stray low reading is not the basal value). Must run
 * after the body-temperature estimator, whose estimates for this push it reads from [samples].
 */
class BasalBodyTemperatureDeriver(
    private val reader: SampleWindowReader,
    private val smoothing: Int = 3,
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> = nights(samples).mapNotNull { night ->
        val estimates = reader.samples(BODY, night.start, night.end, samples)
        if (estimates.size < smoothing) return@mapNotNull null
        val lowest = estimates.windowed(smoothing).minBy { window -> window.map { it.value }.sorted()[smoothing / 2] }
        val settled = lowest.sortedBy { it.value }[smoothing / 2]
        SensorSample(SensorType.BASAL_BODY_TEMPERATURE, settled.value, settled.recordedAt)
    }

    private companion object {
        val BODY = setOf(SensorType.BODY_TEMPERATURE)
    }
}
