package org.sand.ticwatch.phone.ecg

import kotlin.math.abs

/** The line drawn on the ECG paper: millivolt-scaled amplitude at a strip time. */
interface EcgTrace {
    /** Strip time of the newest drawable point. */
    val endMs: Long

    /** Null where the trace has no signal (before the wave starts, across a sensor dropout): the pen lifts. */
    fun millivoltsAt(timeMs: Double): Double?
}

/** The illustrative P-QRS-T of [EcgWaveform] at each measured beat. */
class SyntheticEcgTrace(private val beats: List<EcgBeat>) : EcgTrace {
    /** The newest complex ends with its T wave, after the beat. */
    override val endMs: Long = beats.lastOrNull()?.let { it.offsetMs + T_WAVE_END_MS } ?: 0L

    override fun millivoltsAt(timeMs: Double): Double? = EcgWaveform.millivoltsAt(timeMs, beats)

    private companion object {
        const val T_WAVE_END_MS = 450L
    }
}

/** What the paper draws for one strip: its trace and where its beats sit on it. */
class EcgStrip(val trace: EcgTrace, private val beats: List<EcgBeat>, private val beatShiftMs: Long) {
    val durationMs: Long get() = trace.endMs

    /** Strip time of beat [index] on the drawn trace. */
    fun beatTimeMs(index: Int): Long = beats[index].offsetMs + beatShiftMs

    companion object {
        /**
         * The P-QRS-T complexes at the measured beats. Beat times are a running sum of intervals anchored at the first
         * beat's (batched) arrival; with a [pulse] wave they are shifted onto its peaks and back by the R-peak to wrist
         * pulse-peak delay, so each complex sits where the heart actually beat.
         */
        fun of(beats: List<EcgBeat>, pulse: PulseWaveAnalysis?): EcgStrip {
            val peaks = pulse?.beats?.map { it.peakMs }.orEmpty()
            val shift = if (peaks.isEmpty()) 0L else BeatWaveAlignment.shiftMs(beats, peaks).toLong() - R_TO_PULSE_PEAK_MS
            val shifted = beats.map { it.copy(offsetMs = it.offsetMs + shift) }.filter { it.offsetMs >= 0 }
            return EcgStrip(SyntheticEcgTrace(shifted), beats, shift)
        }

        /** Pre-ejection period plus arm transit plus the pulse's rise: R peak to wrist pulse peak at rest. */
        private const val R_TO_PULSE_PEAK_MS = 300L
    }
}

/**
 * Puts the beats (a running sum of intervals anchored at the first beat's batched arrival) onto the wave's pulse peaks:
 * the shift that matches the most beats within [MATCH_TOLERANCE_MS]. The first beat arrives late, which makes the beat
 * times early, so the search leans negative.
 */
object BeatWaveAlignment {

    fun shiftMs(beats: List<EcgBeat>, peaksMs: List<Double>): Double {
        if (beats.isEmpty() || peaksMs.isEmpty()) return 0.0
        var bestShift = 0.0
        var bestMatches = -1
        var bestError = Double.MAX_VALUE
        var shift = -MAX_EARLY_SHIFT_MS
        while (shift <= MAX_LATE_SHIFT_MS) {
            var matches = 0
            var error = 0.0
            beats.forEach { beat ->
                val distance = nearestDistance(peaksMs, beat.offsetMs + shift)
                if (distance <= MATCH_TOLERANCE_MS) {
                    matches++
                    error += distance
                }
            }
            val better = matches > bestMatches ||
                (matches == bestMatches && (error < bestError || (error == bestError && abs(shift) < abs(bestShift))))
            if (better) {
                bestShift = shift
                bestMatches = matches
                bestError = error
            }
            shift += SHIFT_STEP_MS
        }
        return bestShift
    }

    /** Index into sorted [peaksMs] of the peak within [MATCH_TOLERANCE_MS] of [timeMs], if any. */
    fun matchingPeak(peaksMs: List<Double>, timeMs: Double): Int? {
        val index = peaksMs.binarySearch(timeMs).let { if (it >= 0) return it else -it - 1 }
        return listOf(index - 1, index)
            .filter { it in peaksMs.indices && abs(peaksMs[it] - timeMs) <= MATCH_TOLERANCE_MS }
            .minByOrNull { abs(peaksMs[it] - timeMs) }
    }

    private fun nearestDistance(sorted: List<Double>, time: Double): Double {
        val index = sorted.binarySearch(time).let { if (it >= 0) return 0.0 else -it - 1 }
        val after = sorted.getOrNull(index)?.let { it - time } ?: Double.MAX_VALUE
        val before = sorted.getOrNull(index - 1)?.let { time - it } ?: Double.MAX_VALUE
        return minOf(after, before)
    }

    private const val MAX_EARLY_SHIFT_MS = 1_500.0
    private const val MAX_LATE_SHIFT_MS = 300.0
    private const val SHIFT_STEP_MS = 10.0
    private const val MATCH_TOLERANCE_MS = 150.0
}
