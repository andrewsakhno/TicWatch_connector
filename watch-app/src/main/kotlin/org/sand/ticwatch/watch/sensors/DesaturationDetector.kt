package org.sand.ticwatch.watch.sensors

import java.time.Duration
import java.time.Instant

/**
 * Follows one sensor across a whole night (unlike a [VendorReadingAggregator], which is rebuilt every
 * window) and reports discrete events once they complete.
 */
interface NightEventDetector : WindowSensorListener {
    /** Events completed since the previous call. */
    fun drain(): List<SensorSample>
}

/**
 * Oxygen desaturation events, the signal behind the oxygen desaturation index (ODI) used to screen for
 * sleep apnea: SpO2 falling at least [minDrop] points below the median of the preceding [baselineWindow]
 * and staying down for [minDuration]. The event ends when SpO2 recovers to within [recoveryMargin] of
 * that baseline (or after [maxDuration]); its value is the depth (baseline - nadir). A gap in readings
 * longer than [maxGap] drops the pending event and the baseline, since the trace is no longer continuous.
 */
class DesaturationDetector(
    private val minDrop: Double = 3.0,
    private val recoveryMargin: Double = 1.0,
    private val minDuration: Duration = Duration.ofSeconds(10),
    private val maxDuration: Duration = Duration.ofMinutes(2),
    private val baselineWindow: Duration = Duration.ofMinutes(2),
    private val minBaselineSpan: Duration = Duration.ofMinutes(1),
    private val minBaselineReadings: Int = 10,
    private val maxGap: Duration = Duration.ofSeconds(15),
) : NightEventDetector {
    override val sensorType = VendorSensorTypes.SPO2

    private val recent = ArrayDeque<Pair<Instant, Double>>()
    private val completed = mutableListOf<SensorSample>()
    private var event: PendingEvent? = null

    private class PendingEvent(val start: Instant, val baseline: Double, var nadir: Double)

    override fun onEvent(values: FloatArray, at: Instant) {
        val percent = Spo2Reading.decode(values) ?: return
        val previous = recent.lastOrNull()?.first
        if (previous != null && Duration.between(previous, at) > maxGap) {
            recent.clear()
            event = null
        }
        val pending = event
        if (pending == null) startIfDropped(percent, at) else follow(pending, percent, at)
        // The baseline only learns from readings outside an event, so a long dip can't drag it down.
        if (event == null) remember(percent, at)
    }

    override fun drain(): List<SensorSample> = completed.toList().also { completed.clear() }

    private fun startIfDropped(percent: Double, at: Instant) {
        val baseline = baseline() ?: return
        if (percent <= baseline - minDrop) event = PendingEvent(at, baseline, percent)
    }

    private fun follow(pending: PendingEvent, percent: Double, at: Instant) {
        pending.nadir = minOf(pending.nadir, percent)
        val duration = Duration.between(pending.start, at)
        if (percent < pending.baseline - recoveryMargin && duration < maxDuration) return
        if (duration >= minDuration) {
            completed += SensorSample(SensorType.OXYGEN_DESATURATION, pending.baseline - pending.nadir, pending.start, at)
        }
        event = null
    }

    private fun remember(percent: Double, at: Instant) {
        recent.addLast(at to percent)
        val cutoff = at.minus(baselineWindow)
        while (recent.first().first.isBefore(cutoff)) recent.removeFirst()
    }

    private fun baseline(): Double? {
        if (recent.size < minBaselineReadings) return null
        if (Duration.between(recent.first().first, recent.last().first) < minBaselineSpan) return null
        return recent.map { it.second }.median()
    }
}
