package org.sand.ticwatch.phone.ecg

import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.uiText

/** Overall rhythm call of a strip, most specific finding first. */
enum class EcgRhythm(@StringRes val label: Int, val severity: EcgSeverity) {
    INCONCLUSIVE(R.string.rhythm_inconclusive, EcgSeverity.NOTICE),
    POSSIBLE_ATRIAL_FIBRILLATION(R.string.rhythm_possible_atrial_fibrillation, EcgSeverity.WARNING),
    ECTOPIC_BEATS(R.string.rhythm_ectopic_beats, EcgSeverity.NOTICE),
    SINUS_TACHYCARDIA(R.string.rhythm_sinus_tachycardia, EcgSeverity.NOTICE),
    SINUS_BRADYCARDIA(R.string.rhythm_sinus_bradycardia, EcgSeverity.NOTICE),
    SINUS_ARRHYTHMIA(R.string.rhythm_sinus_arrhythmia, EcgSeverity.NORMAL),
    SINUS_RHYTHM(R.string.rhythm_sinus_rhythm, EcgSeverity.NORMAL),
}

enum class EcgSeverity { NORMAL, NOTICE, WARNING }

/** Vagal (parasympathetic) tone from RMSSD at rest. */
enum class VagalTone(@StringRes val label: Int) {
    LOW(R.string.vagal_tone_low),
    NORMAL(R.string.vagal_tone_normal),
    HIGH(R.string.vagal_tone_high),
}

/** One line of the interpretation. */
data class EcgFinding(val text: UiText, val severity: EcgSeverity)

/** What the rhythm analysis sees in one strip. */
data class EcgAnalysis(
    val rhythm: EcgRhythm,
    val beatCount: Int,
    /** Share of beats the sensor marked clean. */
    val signalQuality: Double,
    val meanHeartRate: Double?,
    val minHeartRate: Double?,
    val maxHeartRate: Double?,
    val sdnnMs: Double?,
    val rmssdMs: Double?,
    val pnn50: Double?,
    val prematureBeats: Int,
    val pauses: Int,
    val vagalTone: VagalTone?,
    /** 0..1: grows with clean beats and signal quality. */
    val confidence: Double,
    val findings: List<EcgFinding>,
    /** Indices (into the recording's beats) of the beats judged premature, for marking on the strip. */
    val prematureBeatIndices: Set<Int>,
    /** The pulse wave's reading, for strips recorded with one. */
    val pulse: PulseWaveAnalysis? = null,
    /** Premature beats whose pulse was weak or missing; null without premature beats or a wave. */
    val confirmedPrematureBeats: Int? = null,
)

/** Turns a rhythm strip into an [EcgAnalysis]. */
fun interface EcgAnalyzer {
    fun analyze(beats: List<EcgBeat>): EcgAnalysis
}

/**
 * Beat-to-beat rhythm analysis of a PPG-derived strip:
 * - premature beats: an interval < [PREMATURE_RATIO] of the local median followed by a compensatory one > [COMPENSATORY_RATIO],
 *   or an isolated one between two normal intervals (within [NORMAL_BAND]; atrial premature beats often reset the rhythm
 *   without a compensatory pause) - either way left out of the variability and fibrillation measures;
 * - pauses: an interval > [PAUSE_RATIO] of the local median (a blocked or missed beat);
 * - atrial fibrillation screen on the ectopy-free series (Dash et al. 2009): normalised RMSSD > [AF_NRMSSD],
 *   Shannon entropy of a [ENTROPY_BINS]-bin histogram > [AF_ENTROPY] and a turning-point ratio within
 *   [AF_TURNING_POINTS] (as random as a shuffled series) must all hold;
 * - rate: < [BRADYCARDIA_BPM] / > [TACHYCARDIA_BPM] bpm; breathing variation when the series is variable but smooth.
 * The watch has no electrodes, so P waves, QRS width, ST and QT can't be assessed - only the rhythm.
 */
class EcgRhythmAnalyzer : EcgAnalyzer {

    override fun analyze(beats: List<EcgBeat>): EcgAnalysis {
        val clean = beats.withIndex().filter { it.value.clean }
        val quality = if (beats.isEmpty()) 0.0 else clean.size.toDouble() / beats.size
        val intervals = clean.map { it.value.rrMs }
        if (clean.size < MIN_CLEAN_BEATS || quality < MIN_QUALITY) return inconclusive(beats.size, quality, intervals)

        val premature = mutableSetOf<Int>()
        var pauses = 0
        intervals.indices.forEach { i ->
            val median = localMedian(intervals, i)
            val next = intervals.getOrNull(i + 1)
            val previous = intervals.getOrNull(i - 1)
            val compensated = next != null && next > COMPENSATORY_RATIO * median
            val isolated = next != null && previous != null && next / median in NORMAL_BAND && previous / median in NORMAL_BAND
            if (intervals[i] < PREMATURE_RATIO * median && (compensated || isolated)) premature += i
            if (intervals[i] > PAUSE_RATIO * median) pauses++
        }
        // A premature beat and its compensatory pause both distort variability measures.
        val excluded = premature.flatMap { listOf(it, it + 1) }.toSet()
        val normal = intervals.filterIndexed { i, _ -> i !in excluded }
        if (normal.size < MIN_NORMAL_INTERVALS) return inconclusive(beats.size, quality, intervals)

        val meanRr = normal.average()
        val sdnn = standardDeviation(normal)
        val rmssd = rmssd(normal)
        val pnn50 = successiveDifferences(normal).let { diffs -> diffs.count { it > PNN50_MS } / diffs.size.coerceAtLeast(1).toDouble() }
        val meanHr = MS_PER_MINUTE / meanRr
        val irregular = rmssd / meanRr > AF_NRMSSD && shannonEntropy(normal) > AF_ENTROPY &&
            turningPointRatio(normal) in AF_TURNING_POINTS
        val variable = sdnn / meanRr > SINUS_ARRHYTHMIA_CV

        val rhythm = when {
            irregular -> EcgRhythm.POSSIBLE_ATRIAL_FIBRILLATION
            premature.isNotEmpty() -> EcgRhythm.ECTOPIC_BEATS
            meanHr > TACHYCARDIA_BPM -> EcgRhythm.SINUS_TACHYCARDIA
            meanHr < BRADYCARDIA_BPM -> EcgRhythm.SINUS_BRADYCARDIA
            variable -> EcgRhythm.SINUS_ARRHYTHMIA
            else -> EcgRhythm.SINUS_RHYTHM
        }
        val tone = when {
            rmssd < LOW_RMSSD_MS -> VagalTone.LOW
            rmssd > HIGH_RMSSD_MS -> VagalTone.HIGH
            else -> VagalTone.NORMAL
        }
        val premIndices = premature.map { clean[it].index }.toSet()
        return EcgAnalysis(
            rhythm = rhythm,
            beatCount = beats.size,
            signalQuality = quality,
            meanHeartRate = meanHr,
            minHeartRate = MS_PER_MINUTE / normal.max(),
            maxHeartRate = MS_PER_MINUTE / normal.min(),
            sdnnMs = sdnn,
            rmssdMs = rmssd,
            pnn50 = pnn50,
            prematureBeats = premature.size,
            pauses = pauses,
            vagalTone = tone.takeIf { !irregular },
            confidence = confidence(clean.size, quality),
            findings = findings(rhythm, meanHr, premature.size, pauses, tone, irregular, quality),
            prematureBeatIndices = premIndices,
        )
    }

    private fun findings(
        rhythm: EcgRhythm,
        meanHr: Double,
        premature: Int,
        pauses: Int,
        tone: VagalTone,
        irregular: Boolean,
        quality: Double,
    ): List<EcgFinding> = buildList {
        add(EcgFinding(uiText(R.string.ecg_f_rhythm, uiText(rhythm.label), meanHr.roundToInt()), rhythm.severity))
        if (premature > 0 && rhythm != EcgRhythm.ECTOPIC_BEATS) {
            add(EcgFinding(uiText(R.string.ecg_f_premature, premature), EcgSeverity.NOTICE))
        }
        if (pauses > 0) {
            add(EcgFinding(uiText(R.string.ecg_f_pauses, pauses, PAUSE_RATIO.toString()), EcgSeverity.NOTICE))
        }
        if (irregular) {
            add(EcgFinding(uiText(R.string.ecg_f_irregular), EcgSeverity.WARNING))
        } else {
            add(EcgFinding(uiText(R.string.ecg_f_vagal, uiText(tone.label)), if (tone == VagalTone.LOW) EcgSeverity.NOTICE else EcgSeverity.NORMAL))
        }
        if (quality < GOOD_QUALITY) {
            add(EcgFinding(uiText(R.string.ecg_f_noisy, ((1 - quality) * PERCENT).roundToInt()), EcgSeverity.NOTICE))
        }
        add(EcgFinding(LIMITATION, EcgSeverity.NORMAL))
    }

    private fun inconclusive(beatCount: Int, quality: Double, intervals: List<Double>) = EcgAnalysis(
        rhythm = EcgRhythm.INCONCLUSIVE,
        beatCount = beatCount,
        signalQuality = quality,
        meanHeartRate = intervals.takeIf { it.isNotEmpty() }?.let { MS_PER_MINUTE / it.average() },
        minHeartRate = null,
        maxHeartRate = null,
        sdnnMs = null,
        rmssdMs = null,
        pnn50 = null,
        prematureBeats = 0,
        pauses = 0,
        vagalTone = null,
        confidence = 0.0,
        findings = listOf(
            EcgFinding(uiText(R.string.ecg_f_too_few, intervals.size, beatCount), EcgSeverity.NOTICE),
            EcgFinding(LIMITATION, EcgSeverity.NORMAL),
        ),
        prematureBeatIndices = emptySet(),
    )

    /** Median of up to [LOCAL_WINDOW] intervals on each side of [index], excluding it. */
    private fun localMedian(intervals: List<Double>, index: Int): Double {
        val neighbours = (index - LOCAL_WINDOW..index + LOCAL_WINDOW)
            .filter { it != index && it in intervals.indices }
            .map { intervals[it] }
            .sorted()
        if (neighbours.isEmpty()) return intervals[index]
        val middle = neighbours.size / 2
        return if (neighbours.size % 2 == 0) (neighbours[middle - 1] + neighbours[middle]) / 2 else neighbours[middle]
    }

    private fun successiveDifferences(values: List<Double>): List<Double> = values.zipWithNext { a, b -> abs(b - a) }

    private fun rmssd(values: List<Double>): Double =
        successiveDifferences(values).takeIf { it.isNotEmpty() }?.let { diffs -> sqrt(diffs.map { it * it }.average()) } ?: 0.0

    private fun standardDeviation(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
    }

    /** Normalised (0..1) Shannon entropy of the interval histogram, the extremes trimmed first. */
    private fun shannonEntropy(values: List<Double>): Double {
        val trimmed = values.sorted().drop(ENTROPY_TRIM).dropLast(ENTROPY_TRIM)
        if (trimmed.size < 2) return 0.0
        val low = trimmed.first()
        val width = (trimmed.last() - low) / ENTROPY_BINS
        if (width <= 0) return 0.0
        val counts = trimmed.groupingBy { ((it - low) / width).toInt().coerceAtMost(ENTROPY_BINS - 1) }.eachCount().values
        val entropy = counts.sumOf { count -> val p = count.toDouble() / trimmed.size; -p * ln(p) }
        return entropy / ln(ENTROPY_BINS.toDouble())
    }

    /** Local extrema per interval, relative to a random series' expected (2n - 4) / 3. */
    private fun turningPointRatio(values: List<Double>): Double {
        if (values.size < 3) return 0.0
        val turning = (1 until values.size - 1).count { i ->
            (values[i] > values[i - 1] && values[i] > values[i + 1]) || (values[i] < values[i - 1] && values[i] < values[i + 1])
        }
        return turning / (values.size - 2).toDouble()
    }

    private fun confidence(cleanBeats: Int, quality: Double): Double =
        (cleanBeats / FULL_CONFIDENCE_BEATS).coerceAtMost(1.0) * quality

    companion object {
        const val MIN_CLEAN_BEATS = 15
        const val MIN_QUALITY = 0.6
        const val MIN_NORMAL_INTERVALS = 5
        const val GOOD_QUALITY = 0.9
        const val PREMATURE_RATIO = 0.8
        const val COMPENSATORY_RATIO = 1.1
        val NORMAL_BAND = 0.85..1.15
        const val PAUSE_RATIO = 1.6
        const val LOCAL_WINDOW = 3
        const val AF_NRMSSD = 0.1
        const val AF_ENTROPY = 0.7
        val AF_TURNING_POINTS = 0.54..0.77
        const val ENTROPY_BINS = 16
        const val ENTROPY_TRIM = 2
        const val SINUS_ARRHYTHMIA_CV = 0.05
        const val BRADYCARDIA_BPM = 50.0
        const val TACHYCARDIA_BPM = 100.0
        const val LOW_RMSSD_MS = 20.0
        const val HIGH_RMSSD_MS = 50.0
        const val PNN50_MS = 50.0
        const val FULL_CONFIDENCE_BEATS = 30.0
        private const val MS_PER_MINUTE = 60_000.0
        private const val PERCENT = 100

        val LIMITATION: UiText = uiText(R.string.ecg_limitation)
    }
}
