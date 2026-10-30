package org.sand.ticwatch.phone.ecg

import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.text.LiteralText
import org.sand.ticwatch.phone.text.uiText

/** Analyzes a whole strip: its beats and, when recorded, its pulse wave. */
fun interface CardiogramAnalyzer {
    fun analyze(beats: List<EcgBeat>, wave: List<EcgWaveSample>): EcgAnalysis
}

/**
 * The [rhythm] reading of the beats refined by the [pulse] wave: premature beats are checked against their pulse
 * (an early beat whose ventricle had little time to fill gives a weak or missing pulse), an irregular rhythm against
 * the beat-to-beat pulse heights, and perfusion, breathing and pulse shape are added as findings.
 */
class PulseAwareCardiogramAnalyzer(
    private val rhythm: EcgAnalyzer,
    private val pulse: PulseWaveAnalyzer,
) : CardiogramAnalyzer {

    override fun analyze(beats: List<EcgBeat>, wave: List<EcgWaveSample>): EcgAnalysis {
        val pulseAnalysis = pulse.analyze(wave) ?: return rhythm.analyze(beats)
        val peaks = pulseAnalysis.beats.map { it.peakMs }
        val shift = BeatWaveAlignment.shiftMs(beats, peaks)
        // A beat during wrist movement is as unreliable as one the sensor flagged noisy.
        val moved = beats.indices.filter { index -> pulseAnalysis.motionMs.any { beats[index].offsetMs + shift in it } }.toSet()
        val analysis = rhythm.analyze(beats.mapIndexed { index, beat -> if (index in moved) beat.copy(clean = false) else beat })
        val medianHeight = pulseAnalysis.beats.map { it.amplitude }.sorted().let { it[it.size / 2] }
        val confirmed = analysis.prematureBeatIndices.count { index ->
            val peak = BeatWaveAlignment.matchingPeak(peaks, beats[index].offsetMs + shift)
            peak == null || pulseAnalysis.beats[peak].amplitude < WEAK_PULSE_RATIO * medianHeight
        }
        val extra = listOfNotNull(
            EcgFinding(uiText(R.string.ecg_f_moved, moved.size), EcgSeverity.NOTICE).takeIf { moved.isNotEmpty() },
        ) + pulseFindings(analysis, pulseAnalysis, confirmed)
        val limitation = analysis.findings.filter { it.text == EcgRhythmAnalyzer.LIMITATION }
        return analysis.copy(
            findings = analysis.findings - limitation.toSet() + extra + limitation,
            pulse = pulseAnalysis,
            confirmedPrematureBeats = confirmed.takeIf { analysis.prematureBeats > 0 },
        )
    }

    private fun pulseFindings(analysis: EcgAnalysis, pulse: PulseWaveAnalysis, confirmed: Int): List<EcgFinding> = buildList {
        if (analysis.prematureBeats > 0) {
            val text = if (confirmed > 0) {
                uiText(R.string.ecg_f_premature_weak, confirmed, analysis.prematureBeats)
            } else {
                uiText(R.string.ecg_f_premature_normal)
            }
            add(EcgFinding(text, EcgSeverity.NOTICE))
        }
        if (analysis.rhythm == EcgRhythm.POSSIBLE_ATRIAL_FIBRILLATION) {
            val varying = pulse.amplitudeVariation > AF_HEIGHT_VARIATION
            add(
                EcgFinding(
                    uiText(if (varying) R.string.ecg_f_af_varying else R.string.ecg_f_af_steady),
                    if (varying) EcgSeverity.WARNING else EcgSeverity.NOTICE,
                ),
            )
        }
        add(
            EcgFinding(
                uiText(R.string.ecg_f_perfusion, format(pulse.perfusionIndexPercent, PERFUSION_DECIMALS)),
                EcgSeverity.NORMAL,
            ),
        )
        pulse.breathing?.let { breathing ->
            val agreement = if (breathing.agreeingSignals > 1) {
                uiText(R.string.ecg_f_breathing_agree, breathing.agreeingSignals)
            } else {
                uiText(R.string.ecg_f_breathing_uncertain)
            }
            add(EcgFinding(uiText(R.string.ecg_f_breathing, breathing.breathsPerMinute.roundToInt(), agreement), EcgSeverity.NORMAL))
        }
        val width = pulse.halfWidthMs?.let { uiText(R.string.ecg_f_half_width, it.roundToInt()) } ?: LiteralText("")
        add(
            EcgFinding(
                uiText(R.string.ecg_f_rise, pulse.riseTimeMs.roundToInt(), width),
                EcgSeverity.NORMAL,
            ),
        )
        pulse.agingIndex?.let {
            add(
                EcgFinding(
                    uiText(R.string.ecg_f_aging, format(it, AGING_DECIMALS)),
                    EcgSeverity.NORMAL,
                ),
            )
        }
    }

    private fun format(value: Double, decimals: Int): String = "%.${decimals}f".format(value)

    private companion object {
        /** A pulse below this share of the median height counts as weak. */
        const val WEAK_PULSE_RATIO = 0.75
        /** Above what breathing and the sensor's gain steps give in a regular rhythm (~0.25 at rest). */
        const val AF_HEIGHT_VARIATION = 0.35
        const val PERFUSION_DECIMALS = 2
        const val AGING_DECIMALS = 2
    }
}
