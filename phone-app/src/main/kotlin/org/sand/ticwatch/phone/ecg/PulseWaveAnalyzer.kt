package org.sand.ticwatch.phone.ecg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One pulse of the wave: foot (onset) and peak times on the strip, the pulse height in raw units. */
data class PulseBeat(val footMs: Double, val peakMs: Double, val amplitude: Double, val halfWidthMs: Double?) {
    val riseTimeMs: Double get() = peakMs - footMs
}

/** Breathing rate and how many of the three PPG modulations (baseline, amplitude, interval) agreed on it. */
data class BreathingEstimate(val breathsPerMinute: Double, val agreeingSignals: Int)

/** What the raw pulse wave adds to a strip. */
data class PulseWaveAnalysis(
    val beats: List<PulseBeat>,
    /** Strip times where the wave swings far beyond a pulse: wrist movement, not the heart. */
    val motionMs: List<ClosedFloatingPointRange<Double>>,
    /** Pulsatile share of the light signal, percent (AC/DC): falls when small vessels constrict. */
    val perfusionIndexPercent: Double,
    /** Median foot-to-peak time: shorter with stiffer arteries or higher pressure. */
    val riseTimeMs: Double,
    /** Median pulse width at half height. */
    val halfWidthMs: Double?,
    /** Coefficient of variation of the pulse heights. */
    val amplitudeVariation: Double,
    val breathing: BreathingEstimate?,
    /**
     * b/a of the averaged pulse's second derivative (Takazawa 1998; rises with arterial ageing). Experimental: the wave
     * is 32 Hz wrist PPG, the index was defined on finger PPG; null when outside the published range.
     */
    val agingIndex: Double?,
)

/** Extracts beats, perfusion, breathing and shape from the raw pulse wave; null when the wave is too short or poor. */
fun interface PulseWaveAnalyzer {
    fun analyze(wave: List<EcgWaveSample>): PulseWaveAnalysis?
}

/**
 * [PulseWaveAnalyzer] on the ~32 Hz `pah815x_ppg` pulse channel. The raw value falls as blood arrives, so the wave is
 * inverted first. Works per contiguous segment (a sensor dropout splits it), resampled to a uniform [GRID_MS] grid.
 */
class RawPulseWaveAnalyzer : PulseWaveAnalyzer {

    override fun analyze(wave: List<EcgWaveSample>): PulseWaveAnalysis? {
        if (wave.size < MIN_SAMPLES) return null
        val segments = segments(wave)
        val beats = segments.flatMap(::beatsOf).filter { it.riseTimeMs in MIN_RISE_MS..MAX_RISE_MS && it.amplitude > 0 }
        val motion = motion(segments, beats)
        val valid = withoutOutliers(beats.filter { beat -> motion.none { beat.peakMs in it } })
        if (valid.size < MIN_BEATS) return null
        val dc = wave.map { it.value.toDouble() }.average()
        val amplitudes = valid.map { it.amplitude }
        return PulseWaveAnalysis(
            beats = valid,
            motionMs = motion,
            perfusionIndexPercent = if (dc == 0.0) 0.0 else median(amplitudes) / dc * PERCENT,
            riseTimeMs = median(valid.map { it.riseTimeMs }),
            halfWidthMs = valid.mapNotNull { it.halfWidthMs }.takeIf { it.size >= MIN_BEATS }?.let(::median),
            amplitudeVariation = standardDeviation(amplitudes) / amplitudes.average(),
            breathing = breathing(valid, segments),
            agingIndex = agingIndex(valid, segments),
        )
    }

    /** A dropout-free run of the wave on a uniform grid, inverted (more blood = higher). */
    private class Segment(val startMs: Double, val values: DoubleArray) {
        val endMs: Double get() = startMs + (values.size - 1) * GRID_MS

        fun at(timeMs: Double): Double? {
            val position = (timeMs - startMs) / GRID_MS
            if (position < 0 || position > values.size - 1) return null
            val low = position.toInt().coerceAtMost(values.size - 2)
            val fraction = position - low
            return values[low] + (values[low + 1] - values[low]) * fraction
        }
    }

    private fun segments(wave: List<EcgWaveSample>): List<Segment> {
        val runs = mutableListOf<MutableList<EcgWaveSample>>(mutableListOf(wave.first()))
        wave.zipWithNext { previous, next ->
            if (next.offsetMs - previous.offsetMs > MAX_GAP_MS) runs += mutableListOf<EcgWaveSample>()
            runs.last() += next
        }
        return runs.filter { it.size >= MIN_SEGMENT_SAMPLES }.map { run ->
            val start = run.first().offsetMs.toDouble()
            val count = ((run.last().offsetMs - start) / GRID_MS).toInt() + 1
            var index = 0
            Segment(start, DoubleArray(count) { step ->
                val time = start + step * GRID_MS
                while (index < run.size - 2 && run[index + 1].offsetMs <= time) index++
                val a = run[index]
                val b = run[index + 1]
                val span = (b.offsetMs - a.offsetMs).toDouble()
                val fraction = if (span == 0.0) 0.0 else ((time - a.offsetMs) / span).coerceIn(0.0, 1.0)
                -(a.value + (b.value - a.value) * fraction)
            })
        }
    }

    private fun beatsOf(segment: Segment): List<PulseBeat> {
        val values = segment.values
        val detrended = detrend(values)
        val refractory = (REFRACTORY_MS / GRID_MS).toInt()
        val peaks = values.indices.filter { index ->
            val from = (index - refractory).coerceAtLeast(0)
            val to = (index + refractory).coerceAtMost(values.size - 1)
            detrended[index] > 0 && (from..to).all { it == index || detrended[it] < detrended[index] || (it > index && detrended[it] == detrended[index]) }
        }
        return peaks.mapIndexedNotNull { order, peak ->
            val searchFrom = if (order == 0) (peak - refractory * 2).coerceAtLeast(0) else peaks[order - 1]
            if (peak - searchFrom < 2) return@mapIndexedNotNull null
            val foot = (searchFrom until peak).minBy { values[it] }
            if (foot == searchFrom && order == 0) return@mapIndexedNotNull null
            val peakTime = segment.startMs + refinedIndex(values, peak) * GRID_MS
            val footTime = segment.startMs + foot * GRID_MS
            val amplitude = values[peak] - values[foot]
            val nextFoot = peaks.getOrNull(order + 1)?.let { next -> (peak until next).minBy { values[it] } } ?: (values.size - 1)
            PulseBeat(footTime, peakTime, amplitude, halfWidth(segment, foot, peak, nextFoot, values[foot] + amplitude / 2))
        }
    }

    /**
     * Stretches where the detrended wave strays more than [MOTION_FACTOR] median pulse heights from its baseline,
     * widened by [MOTION_MARGIN_MS] and merged.
     */
    private fun motion(segments: List<Segment>, beats: List<PulseBeat>): List<ClosedFloatingPointRange<Double>> {
        if (beats.isEmpty()) return emptyList()
        val limit = MOTION_FACTOR * median(beats.map { it.amplitude })
        val ranges = segments.flatMap { segment ->
            detrend(segment.values).withIndex().filter { abs(it.value) > limit }.map { (index, _) ->
                val time = segment.startMs + index * GRID_MS
                (time - MOTION_MARGIN_MS)..(time + MOTION_MARGIN_MS)
            }
        }
        return ranges.fold(mutableListOf()) { merged, range ->
            val last = merged.lastOrNull()
            if (last != null && range.start <= last.endInclusive) merged[merged.size - 1] = last.start..maxOf(last.endInclusive, range.endInclusive)
            else merged += range
            merged
        }
    }

    /** Sub-sample peak position by a parabola through the peak and its neighbours. */
    private fun refinedIndex(values: DoubleArray, index: Int): Double {
        if (index == 0 || index == values.size - 1) return index.toDouble()
        val left = values[index - 1]
        val centre = values[index]
        val right = values[index + 1]
        val denominator = left - 2 * centre + right
        return if (denominator == 0.0) index.toDouble() else index + (left - right) / (2 * denominator)
    }

    private fun halfWidth(segment: Segment, foot: Int, peak: Int, nextFoot: Int, level: Double): Double? {
        val values = segment.values
        val rising = (foot until peak).firstOrNull { values[it] <= level && values[it + 1] > level } ?: return null
        val falling = (peak until nextFoot).firstOrNull { values[it] >= level && values[it + 1] < level } ?: return null
        val up = rising + (level - values[rising]) / (values[rising + 1] - values[rising])
        val down = falling + (values[falling] - level) / (values[falling] - values[falling + 1])
        return (down - up) * GRID_MS
    }

    private fun detrend(values: DoubleArray): DoubleArray {
        val half = (BASELINE_HALF_WINDOW_MS / GRID_MS).toInt()
        return DoubleArray(values.size) { index ->
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(values.size - 1)
            var sum = 0.0
            for (other in from..to) sum += values[other]
            values[index] - sum / (to - from + 1)
        }
    }

    /** Drops beats whose rise time or height is more than [OUTLIER_MADS] median absolute deviations off. */
    private fun withoutOutliers(beats: List<PulseBeat>): List<PulseBeat> {
        if (beats.size < MIN_BEATS) return beats
        fun keep(values: List<Double>): (Double) -> Boolean {
            val centre = median(values)
            val spread = median(values.map { abs(it - centre) }).coerceAtLeast(MIN_SPREAD * abs(centre))
            return { abs(it - centre) <= OUTLIER_MADS * spread }
        }
        val rise = keep(beats.map { it.riseTimeMs })
        val height = keep(beats.map { it.amplitude })
        return beats.filter { rise(it.riseTimeMs) && height(it.amplitude) }
    }

    /**
     * Breathing modulates the pulse three ways (Charlton et al. 2016): the baseline (foot level), the pulse height and
     * the beat interval. Each series is resampled at [BREATH_SAMPLE_HZ] and its strongest 0.1-0.5 Hz component taken;
     * estimates within [BREATH_AGREEMENT_HZ] of each other are averaged.
     */
    private fun breathing(beats: List<PulseBeat>, segments: List<Segment>): BreathingEstimate? {
        if (beats.last().peakMs - beats.first().peakMs < MIN_BREATH_SPAN_MS) return null
        val baseline = beats.mapNotNull { beat -> segments.firstNotNullOfOrNull { it.at(beat.footMs) }?.let { beat.peakMs to it } }
        val height = beats.map { it.peakMs to it.amplitude }
        val interval = beats.zipWithNext { a, b -> b.peakMs to (b.peakMs - a.peakMs) }.filter { it.second <= MAX_INTERVAL_MS }
        val estimates = listOf(baseline, height, interval).mapNotNull(::dominantBreathingHz)
        if (estimates.isEmpty()) return null
        val best = estimates.maxBy { it.second }.first
        val agreeing = estimates.map { it.first }.filter { abs(it - best) <= BREATH_AGREEMENT_HZ }
        return BreathingEstimate(agreeing.average() * SECONDS_PER_MINUTE, agreeing.size)
    }

    /** Strongest breathing-band frequency of a beat-sampled series with its peak-to-mean power ratio, if clear enough. */
    private fun dominantBreathingHz(series: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (series.size < MIN_BEATS) return null
        val step = MS_PER_SECOND / BREATH_SAMPLE_HZ
        val start = series.first().first
        val count = ((series.last().first - start) / step).toInt() + 1
        var index = 0
        val resampled = DoubleArray(count) { sample ->
            val time = start + sample * step
            while (index < series.size - 2 && series[index + 1].first <= time) index++
            val (t0, v0) = series[index]
            val (t1, v1) = series[index + 1]
            v0 + (v1 - v0) * ((time - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        }
        val mean = resampled.average()
        val slope = linearSlope(resampled)
        val windowed = DoubleArray(count) { i ->
            val hann = 0.5 - 0.5 * cos(2 * PI * i / (count - 1))
            (resampled[i] - mean - slope * (i - (count - 1) / 2.0)) * hann
        }
        // Scanned beyond the band: a peak on its edge is slow drift or noise leaking in, not breathing.
        val powers = generateSequence(MIN_BREATH_HZ - BREATH_GUARD_HZ) { it + BREATH_SCAN_STEP_HZ }.takeWhile { it <= MAX_BREATH_HZ + BREATH_GUARD_HZ }.map { hz ->
            var re = 0.0
            var im = 0.0
            windowed.forEachIndexed { i, value ->
                val angle = 2 * PI * hz * i / BREATH_SAMPLE_HZ
                re += value * cos(angle)
                im -= value * sin(angle)
            }
            hz to re * re + im * im
        }.toList()
        val peak = powers.maxBy { it.second }
        val ratio = peak.second / powers.map { it.second }.average()
        return if (ratio >= MIN_BREATH_PEAK_RATIO && peak.first in MIN_BREATH_HZ..MAX_BREATH_HZ) peak.first to ratio else null
    }

    private fun linearSlope(values: DoubleArray): Double {
        val centre = (values.size - 1) / 2.0
        var numerator = 0.0
        var denominator = 0.0
        values.forEachIndexed { i, value ->
            numerator += (i - centre) * value
            denominator += (i - centre) * (i - centre)
        }
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    /**
     * Averages the beats aligned on their foot (their random sub-sample phases add resolution beyond 32 Hz), smooths
     * the template and takes b/a of its second derivative: a = the early positive wave, b = the following negative one.
     */
    private fun agingIndex(beats: List<PulseBeat>, segments: List<Segment>): Double? {
        val length = (median(beats.zipWithNext { a, b -> b.footMs - a.footMs }.ifEmpty { return null }) * TEMPLATE_SHARE_OF_BEAT / TEMPLATE_STEP_MS).toInt()
        val sums = DoubleArray(length)
        var count = 0
        beats.forEach { beat ->
            val segment = segments.firstOrNull { beat.footMs >= it.startMs && beat.footMs + length * TEMPLATE_STEP_MS <= it.endMs } ?: return@forEach
            val foot = segment.at(beat.footMs) ?: return@forEach
            for (i in 0 until length) sums[i] += ((segment.at(beat.footMs + i * TEMPLATE_STEP_MS) ?: foot) - foot) / beat.amplitude
            count++
        }
        if (count < MIN_BEATS) return null
        val template = smooth(DoubleArray(length) { sums[it] / count })
        val second = DoubleArray(length) { i -> if (i == 0 || i == length - 1) 0.0 else template[i - 1] - 2 * template[i] + template[i + 1] }
        val riseSteps = (median(beats.map { it.riseTimeMs }) / TEMPLATE_STEP_MS).toInt().coerceIn(2, length - 2)
        val a = (1..riseSteps).maxBy { second[it] }
        if (second[a] <= 0) return null
        val b = (a + 1 until length - 1).firstOrNull { second[it] < second[it - 1] && second[it] <= second[it + 1] && second[it] < 0 } ?: return null
        return (second[b] / second[a]).takeIf { it in PLAUSIBLE_AGING_INDEX }
    }

    private fun smooth(values: DoubleArray): DoubleArray {
        val half = SMOOTHING_HALF_WINDOW
        return DoubleArray(values.size) { index ->
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(values.size - 1)
            (from..to).sumOf { values[it] } / (to - from + 1)
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private companion object {
        const val GRID_MS = 31.25
        const val MAX_GAP_MS = 200L
        const val MIN_SAMPLES = 64
        const val MIN_SEGMENT_SAMPLES = 32
        const val MIN_BEATS = 5
        const val BASELINE_HALF_WINDOW_MS = 750.0

        /** No two pulses closer than this (200 bpm). */
        const val REFRACTORY_MS = 300.0
        const val MIN_RISE_MS = 60.0
        const val MAX_RISE_MS = 450.0
        const val OUTLIER_MADS = 4.0
        const val MOTION_FACTOR = 1.5
        const val MOTION_MARGIN_MS = 500.0
        const val MIN_SPREAD = 0.05
        const val PERCENT = 100.0

        const val BREATH_SAMPLE_HZ = 4.0
        const val MIN_BREATH_HZ = 0.1
        const val MAX_BREATH_HZ = 0.5
        const val BREATH_SCAN_STEP_HZ = 0.005
        const val BREATH_GUARD_HZ = 0.05
        const val MIN_BREATH_PEAK_RATIO = 3.0
        const val BREATH_AGREEMENT_HZ = 0.04
        const val MIN_BREATH_SPAN_MS = 20_000.0

        /** Longer gaps span a dropout or a missed pulse (30 bpm). */
        const val MAX_INTERVAL_MS = 2_000.0
        const val SECONDS_PER_MINUTE = 60.0
        const val MS_PER_SECOND = 1_000.0

        const val TEMPLATE_STEP_MS = 5.0
        const val TEMPLATE_SHARE_OF_BEAT = 0.8
        const val SMOOTHING_HALF_WINDOW = 4

        /** Published b/a spans about -1.2 (young) to -0.2 (old); outside it the sensor's smoothing hid the b wave. */
        val PLAUSIBLE_AGING_INDEX = -1.2..-0.2
    }
}
