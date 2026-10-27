package org.sand.ticwatch.watch.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One clean heartbeat: [atSeconds] since the first beat of the series, [intervalMs] the RR interval ending it. */
data class Beat(val atSeconds: Double, val intervalMs: Double)

/**
 * Breathing rate from heartbeat timing. Respiratory sinus arrhythmia makes RR intervals shorten on
 * inhale and lengthen on exhale, so the tachogram oscillates at the breathing frequency. The series
 * is resampled evenly at [sampleRateHz], detrended and Hann-windowed, and the strongest spectral peak
 * in [band] wins - but only if it stands [minPeakProminence] times above the band's mean power: a flat
 * spectrum (irregular breathing, ectopy, motion) yields nothing rather than a guess.
 */
class RespiratorySinusArrhythmiaEstimator(
    private val sampleRateHz: Double = 4.0,
    private val band: ClosedFloatingPointRange<Double> = 0.1..0.5,
    private val frequencyStepHz: Double = 0.002,
    private val minPeakProminence: Double = 3.0,
    private val minSpanSeconds: Double = 90.0,
    private val minBeats: Int = 80,
) {
    /** Breaths per minute, or null when [beats] (ascending by time) carry no clear breathing rhythm. */
    fun breathsPerMinute(beats: List<Beat>): Double? {
        if (beats.size < minBeats) return null
        val start = beats.first().atSeconds
        val span = beats.last().atSeconds - start
        if (span < minSpanSeconds) return null

        val series = resample(beats, start, (span * sampleRateHz).toInt())
        detrend(series)
        applyHannWindow(series)

        val powers = frequencies().map { it to power(series, it) }
        val (peakFrequency, peakPower) = powers.maxBy { it.second }
        val meanPower = powers.sumOf { it.second } / powers.size
        if (meanPower <= 0.0 || peakPower / meanPower < minPeakProminence) return null
        return peakFrequency * SECONDS_PER_MINUTE
    }

    /** Linear interpolation of the RR values onto an even grid of [count] points. */
    private fun resample(beats: List<Beat>, start: Double, count: Int): DoubleArray {
        var segment = 0
        return DoubleArray(count) { index ->
            val t = start + index / sampleRateHz
            while (segment < beats.size - 2 && beats[segment + 1].atSeconds < t) segment++
            val left = beats[segment]
            val right = beats[segment + 1]
            val span = right.atSeconds - left.atSeconds
            if (span <= 0.0) left.intervalMs
            else left.intervalMs + (right.intervalMs - left.intervalMs) * ((t - left.atSeconds) / span).coerceIn(0.0, 1.0)
        }
    }

    /** Removes the least-squares line, so a slow heart-rate drift doesn't swamp the band. */
    private fun detrend(series: DoubleArray) {
        val n = series.size
        val meanX = (n - 1) / 2.0
        val meanY = series.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in series.indices) {
            covariance += (i - meanX) * (series[i] - meanY)
            variance += (i - meanX) * (i - meanX)
        }
        val slope = if (variance == 0.0) 0.0 else covariance / variance
        for (i in series.indices) series[i] -= meanY + slope * (i - meanX)
    }

    private fun applyHannWindow(series: DoubleArray) {
        val last = series.size - 1
        if (last <= 0) return
        for (i in series.indices) series[i] *= HALF * (1 - cos(2 * PI * i / last))
    }

    private fun frequencies(): List<Double> =
        generateSequence(band.start) { it + frequencyStepHz }.takeWhile { it <= band.endInclusive }.toList()

    /** Periodogram power at [frequency], evaluated directly (the band is narrow, an FFT buys nothing). */
    private fun power(series: DoubleArray, frequency: Double): Double {
        var re = 0.0
        var im = 0.0
        val step = 2 * PI * frequency / sampleRateHz
        for (i in series.indices) {
            re += series[i] * cos(step * i)
            im += series[i] * sin(step * i)
        }
        return re * re + im * im
    }

    private companion object {
        const val SECONDS_PER_MINUTE = 60.0
        const val HALF = 0.5
    }
}
