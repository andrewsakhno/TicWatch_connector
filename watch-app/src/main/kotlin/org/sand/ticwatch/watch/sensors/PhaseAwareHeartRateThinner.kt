package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant

/** How densely the passive ~1 Hz heart rate goes to the phone. */
enum class HeartRateListeningPhase {
    /** Awake, at rest or in zones 1-2: one median per period. */
    REST,

    /** Above zone 2, a resting alert, a workout or a live screen: every reading. */
    FULL_STREAM,

    /** Asleep: one median per period, as the night monitor reports at 1 Hz. */
    SLEEP,
}

/** Decides the [HeartRateListeningPhase] of each heart-rate reading; may keep state across readings. */
fun interface HeartRatePhasePolicy {
    fun phaseOf(sample: SensorSample): HeartRateListeningPhase
}

/**
 * [HeartRateListeningPhase.FULL_STREAM] while [fullStream] says so (workout, live sync, resting alert) or until
 * [stableNormal] of the readings' own time has passed since the last one above [zone2Ceiling]; otherwise
 * [HeartRateListeningPhase.SLEEP] while [asleep], else [HeartRateListeningPhase.REST]. Asleep wins over a high
 * reading, so the night keeps its thinning and anomalies there reach the full stream through the resting alert.
 */
class ZoneHeartRatePhasePolicy(
    private val fullStream: () -> Boolean,
    private val asleep: () -> Boolean,
    private val zone2Ceiling: () -> Double,
    private val stableNormal: Duration,
) : HeartRatePhasePolicy {

    private var lastElevatedAt: Instant? = null

    override fun phaseOf(sample: SensorSample): HeartRateListeningPhase {
        if (sample.value > zone2Ceiling()) lastElevatedAt = sample.recordedAt
        return when {
            fullStream() -> HeartRateListeningPhase.FULL_STREAM
            asleep() -> HeartRateListeningPhase.SLEEP
            recentlyElevated(sample.recordedAt) -> HeartRateListeningPhase.FULL_STREAM
            else -> HeartRateListeningPhase.REST
        }
    }

    private fun recentlyElevated(now: Instant): Boolean =
        lastElevatedAt?.let { Duration.between(it, now) < stableNormal } == true
}

/**
 * Thins the heart rate on its way to the phone by [HeartRatePhasePolicy]: a phase with an entry in [periods] sends one
 * median per period of the readings' own time instead of the ~1 Hz Health Services reports, whose rows otherwise fill
 * the sync buffer and trigger a flush every few minutes; a phase without one passes every reading. A phase change
 * closes the open period at once. Every other type passes untouched, and readings with no contact (0 bpm) are dropped
 * while thinning, as the phone ignores them anyway. Sits after the watch's own consumers (live readings, adaptive
 * triggers), which still see every reading.
 *
 * The open period lives in memory and goes out with the next heart-rate reading, so a killed process loses at most
 * that one period.
 */
class PhaseAwareHeartRateThinner(
    private val periods: Map<HeartRateListeningPhase, Duration>,
    private val policy: HeartRatePhasePolicy,
) {

    private data class Bucket(val phase: HeartRateListeningPhase, val index: Long)

    private class OpenPeriod(val bucket: Bucket) {
        val readings = mutableListOf<SensorSample>()

        fun median() = SensorSample(SensorType.HEART_RATE, readings.map { it.value }.median(), readings.last().recordedAt)
    }

    fun thin(samples: Flow<SensorSample>): Flow<SensorSample> = flow {
        var open: OpenPeriod? = null
        samples.collect { sample ->
            if (sample.type != SensorType.HEART_RATE) {
                emit(sample)
                return@collect
            }
            val phase = policy.phaseOf(sample)
            val bucket = periods[phase]?.let { Bucket(phase, sample.recordedAt.toEpochMilli() / it.toMillis()) }
            open?.takeIf { it.bucket != bucket }?.let { ending ->
                emit(ending.median())
                open = null
            }
            when {
                bucket == null -> emit(sample)
                sample.value > 0 -> (open ?: OpenPeriod(bucket).also { open = it }).readings += sample
            }
        }
    }
}
