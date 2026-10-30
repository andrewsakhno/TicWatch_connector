package org.sand.ticwatch.watch.sensors

import android.hardware.SensorManager
import org.sand.ticwatch.watch.ecg.VendorPpgWaveSource
import java.time.Duration
import java.time.Instant

/**
 * Pulse-wave amplitude drops on the raw PPG, the signal behind peripheral-arterial-tone apnea tests: an apnea ending
 * in an arousal constricts the finger and wrist vessels, shrinking the pulse. The pulse height is the spread
 * ([UPPER_QUANTILE] - [LOWER_QUANTILE]) of the pulse channel over each [envelopeWindow] (at least two beats); an event
 * starts when it falls [minDrop] below the median of the preceding [baselineWindow], lasts at least [minDuration] and
 * ends on recovering to within [recoveryMargin] (or after [maxDuration]). Its value is the depth in percent. A gap in
 * samples longer than [maxGap] drops the pending event and the baseline.
 */
class PulseAmplitudeDropDetector(
    override val sensorType: Int,
    private val pulseChannel: Int = VendorPpgWaveSource.PULSE_CHANNEL,
    private val envelopeWindow: Duration = Duration.ofSeconds(2),
    private val minDrop: Double = 0.3,
    private val recoveryMargin: Double = 0.15,
    private val minDuration: Duration = Duration.ofSeconds(10),
    private val maxDuration: Duration = Duration.ofMinutes(2),
    private val baselineWindow: Duration = Duration.ofMinutes(2),
    private val minBaselineEnvelopes: Int = 30,
    private val maxGap: Duration = Duration.ofSeconds(5),
) : NightEventDetector {

    /** The pulse shape needs every sample (~32 Hz), not the default 5 Hz. */
    override val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_FASTEST

    private val window = mutableListOf<Float>()
    private var windowStart: Instant? = null
    private var lastSample: Instant? = null
    private val envelopes = ArrayDeque<Pair<Instant, Double>>()
    private val completed = mutableListOf<SensorSample>()
    private var event: PendingDrop? = null

    private class PendingDrop(val start: Instant, val baseline: Double, var lowest: Double)

    override fun onEvent(values: FloatArray, at: Instant) {
        if (values.size <= pulseChannel) return
        val previous = lastSample
        lastSample = at
        if (previous != null && Duration.between(previous, at) > maxGap) reset()
        val start = windowStart ?: at.also { windowStart = it }
        window += values[pulseChannel]
        if (Duration.between(start, at) < envelopeWindow) return
        onEnvelope(spread(window), at)
        window.clear()
        windowStart = null
    }

    override fun drain(): List<SensorSample> = completed.toList().also { completed.clear() }

    private fun onEnvelope(height: Double, at: Instant) {
        val pending = event
        if (pending == null) {
            val baseline = baseline()
            if (baseline != null && height <= baseline * (1 - minDrop)) event = PendingDrop(at, baseline, height)
        } else {
            pending.lowest = minOf(pending.lowest, height)
            val duration = Duration.between(pending.start, at)
            if (height < pending.baseline * (1 - recoveryMargin) && duration < maxDuration) return
            if (duration >= minDuration) {
                completed += SensorSample(SensorType.PULSE_AMPLITUDE_DROP, (1 - pending.lowest / pending.baseline) * PERCENT, pending.start, at)
            }
            event = null
        }
        // The baseline only learns outside an event, so a long drop can't drag it down.
        if (event == null) {
            envelopes.addLast(at to height)
            val cutoff = at.minus(baselineWindow)
            while (envelopes.first().first.isBefore(cutoff)) envelopes.removeFirst()
        }
    }

    private fun baseline(): Double? = envelopes.takeIf { it.size >= minBaselineEnvelopes }?.map { it.second }?.median()

    private fun spread(values: List<Float>): Double {
        val sorted = values.sorted()
        val upper = sorted[((sorted.size - 1) * UPPER_QUANTILE).toInt()]
        val lower = sorted[((sorted.size - 1) * LOWER_QUANTILE).toInt()]
        return (upper - lower).toDouble()
    }

    private fun reset() {
        window.clear()
        windowStart = null
        envelopes.clear()
        event = null
    }

    private companion object {
        const val UPPER_QUANTILE = 0.9
        const val LOWER_QUANTILE = 0.1
        const val PERCENT = 100.0
    }
}
