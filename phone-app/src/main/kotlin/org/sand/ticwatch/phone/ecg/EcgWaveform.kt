package org.sand.ticwatch.phone.ecg

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Draws a beat-synchronous trace for the paper strip: a textbook P-QRS-T complex (sum of Gaussians, McSharry et al.
 * 2003) centred on each detected beat, its T wave scaled by Bazett's sqrt(RR). The timing is measured; the shape is
 * illustrative - the watch records no electrical waveform.
 */
object EcgWaveform {

    /** Millivolts at [timeMs] of the strip; [beats] sorted by offset. */
    fun millivoltsAt(timeMs: Double, beats: List<EcgBeat>, from: Int = firstNear(beats, timeMs)): Double {
        var value = 0.0
        var index = from
        while (index < beats.size && beats[index].offsetMs - timeMs < LEAD_MS) {
            val beat = beats[index]
            val dt = (timeMs - beat.offsetMs) / MS_PER_SECOND
            if (dt > -LEAD_MS / MS_PER_SECOND) value += complex(dt, beat)
            index++
        }
        return value
    }

    /** Index of the first beat whose complex can still reach [timeMs]. */
    fun firstNear(beats: List<EcgBeat>, timeMs: Double): Int {
        var low = 0
        var high = beats.size
        while (low < high) {
            val mid = (low + high) / 2
            if (beats[mid].offsetMs < timeMs - TAIL_MS) low = mid + 1 else high = mid
        }
        return low
    }

    private fun complex(dt: Double, beat: EcgBeat): Double {
        val rrScale = sqrt(beat.rrMs / MS_PER_SECOND)
        val amplitude = if (beat.clean) 1.0 else NOISY_AMPLITUDE
        return amplitude * WAVES.sumOf { wave ->
            val centre = if (wave.scalesWithRr) wave.centre * rrScale else wave.centre
            val width = if (wave.scalesWithRr) wave.width * rrScale else wave.width
            val x = (dt - centre) / width
            wave.millivolts * exp(-x * x / 2)
        }
    }

    /** One Gaussian wave: centre and width in seconds from the R peak. */
    private class Wave(val centre: Double, val millivolts: Double, val width: Double, val scalesWithRr: Boolean = false)

    private val WAVES = listOf(
        Wave(centre = -0.17, millivolts = 0.12, width = 0.022), // P
        Wave(centre = -0.035, millivolts = -0.12, width = 0.009), // Q
        Wave(centre = 0.0, millivolts = 1.1, width = 0.011), // R
        Wave(centre = 0.035, millivolts = -0.28, width = 0.010), // S
        Wave(centre = 0.28, millivolts = 0.32, width = 0.055, scalesWithRr = true), // T
    )

    /** A complex spans from its P wave (before the beat) to the end of its T wave (after it). */
    private const val LEAD_MS = 300.0
    const val TAIL_MS = 700.0
    private const val MS_PER_SECOND = 1000.0

    /** Beats the sensor flagged noisy are drawn smaller. */
    private const val NOISY_AMPLITUDE = 0.6
}
