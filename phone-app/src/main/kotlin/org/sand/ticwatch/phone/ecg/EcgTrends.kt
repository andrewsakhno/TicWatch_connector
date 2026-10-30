package org.sand.ticwatch.phone.ecg

import androidx.annotation.StringRes
import java.time.Instant
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.insights.Trend
import org.sand.ticwatch.phone.insights.TrendPoint

/** A per-strip value charted across all stored cardiograms. */
enum class EcgTrendMetric(@StringRes val label: Int, private val read: (EcgAnalysis) -> Double?) {
    /** Share of beats that were neither premature nor a pause; inconclusive strips are left out. */
    REGULAR_RHYTHM(R.string.ecg_trend_regular_rhythm, { analysis ->
        analysis.takeIf { it.rhythm != EcgRhythm.INCONCLUSIVE && it.beatCount > 0 }
            ?.let { (it.beatCount - it.prematureBeats - it.pauses).coerceAtLeast(0) * PERCENT / it.beatCount }
    }),
    PERFUSION_INDEX(R.string.ecg_trend_perfusion_index, { it.pulse?.perfusionIndexPercent }),
    RISE_TIME(R.string.ecg_trend_rise_time, { it.pulse?.riseTimeMs }),
    RMSSD(R.string.ecg_trend_rmssd, { it.rmssdMs }),
    SDNN(R.string.ecg_trend_sdnn, { it.sdnnMs }),
    PNN50(R.string.ecg_trend_pnn50, { analysis -> analysis.pnn50?.let { it * PERCENT } });

    fun valueOf(analysis: EcgAnalysis): Double? = read(analysis)
}

/** The charts of the cardiograms list: each group shares one chart (variability = RMSSD + SDNN + pNN50). */
enum class EcgTrendChart(@StringRes val title: Int, val metrics: List<EcgTrendMetric>) {
    REGULAR_RHYTHM(R.string.ecg_trend_regular_rhythm, listOf(EcgTrendMetric.REGULAR_RHYTHM)),
    PERFUSION_INDEX(R.string.ecg_trend_perfusion_index, listOf(EcgTrendMetric.PERFUSION_INDEX)),
    RISE_TIME(R.string.ecg_trend_rise_time, listOf(EcgTrendMetric.RISE_TIME)),
    VARIABILITY(R.string.ecg_trend_variability, listOf(EcgTrendMetric.RMSSD, EcgTrendMetric.SDNN, EcgTrendMetric.PNN50)),
}

/** One [Trend] per metric over the same span (oldest .. newest strip), so the charts line up. */
data class EcgTrends(val series: Map<EcgTrendMetric, Trend>) {
    companion object {
        fun of(strips: List<Pair<Instant, EcgAnalysis>>): EcgTrends {
            val oldestFirst = strips.sortedBy { it.first }
            val start = oldestFirst.firstOrNull()?.first ?: Instant.EPOCH
            val end = oldestFirst.lastOrNull()?.first ?: start
            return EcgTrends(
                EcgTrendMetric.entries.associateWith { metric ->
                    Trend(oldestFirst.mapNotNull { (at, analysis) -> metric.valueOf(analysis)?.let { TrendPoint(at, it) } }, start, end)
                },
            )
        }
    }
}

private const val PERCENT = 100.0
