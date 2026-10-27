package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.insights.HrvBaseline
import org.sand.ticwatch.phone.insights.HrvStatusLevel
import org.sand.ticwatch.phone.insights.LoadBalance
import org.sand.ticwatch.phone.insights.ReadinessStatus
import org.sand.ticwatch.phone.insights.SleepQuality
import org.sand.ticwatch.phone.insights.StressLevel
import org.sand.ticwatch.phone.spaceweather.GeomagneticLevel

/** One colored stretch of a [GaugeScale], covering values up to [upTo]. */
data class GaugeBand(val label: String, val upTo: Double, val color: Color)

/**
 * A value range split into colored [bands] (ascending [GaugeBand.upTo]), drawn by [ColorScaleBar].
 * Mirrors watch-app's `dashboard/GaugeScale.kt` (no shared module between the apps).
 */
class GaugeScale(val min: Double, val max: Double, val bands: List<GaugeBand>) {

    fun bandOf(value: Double): GaugeBand = bands.firstOrNull { value <= it.upTo } ?: bands.last()

    fun fraction(value: Double): Float = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

object GaugeColors {
    val GOOD = Color(0xFF66BB6A)
    val FAIR = Color(0xFFFFCA28)
    val POOR = Color(0xFFEF5350)
    val REST = Color(0xFF42A5F5)
}

/** Scales of the Metrics cards; band edges come from the insight enums so the label and the bar agree. */
object GaugeScales {
    private const val SCORE_MIN = 0.0
    private const val SCORE_MAX = 100.0
    private const val RMSSD_MIN_MS = 0.0
    private const val RMSSD_MAX_MS = 100.0
    private const val SLEEP_MIN_HOURS = 0.0
    private const val SLEEP_MAX_HOURS = 10.0
    private const val SLEEP_SHORT_HOURS = 6.0
    private const val SLEEP_FAIR_HOURS = 7.0
    private const val LOAD_RATIO_MIN = 0.0
    private const val LOAD_RATIO_MAX = 2.0
    private const val LIGHT_SCALE_GOALS = 2.0
    private const val KP_MIN = 0.0
    private const val KP_MAX = 9.0
    private const val KP_ROUNDING = 0.5

    val READINESS = GaugeScale(
        min = SCORE_MIN, max = SCORE_MAX,
        bands = listOf(
            GaugeBand(ReadinessStatus.REST_NEEDED.label, ReadinessStatus.NORMAL.minScore.toDouble(), GaugeColors.POOR),
            GaugeBand(ReadinessStatus.NORMAL.label, ReadinessStatus.RECOVERED.minScore.toDouble(), GaugeColors.FAIR),
            GaugeBand(ReadinessStatus.RECOVERED.label, Double.POSITIVE_INFINITY, GaugeColors.GOOD),
        ),
    )

    /** Stress from RMSSD: higher vagal tone means lower stress. */
    val STRESS_RMSSD = GaugeScale(
        min = RMSSD_MIN_MS, max = RMSSD_MAX_MS,
        bands = listOf(
            GaugeBand(StressLevel.HIGH.label, StressLevel.MODERATE.minRmssdMs, GaugeColors.POOR),
            GaugeBand(StressLevel.MODERATE.label, StressLevel.LOW.minRmssdMs, GaugeColors.FAIR),
            GaugeBand(StressLevel.LOW.label, StressLevel.REST.minRmssdMs, GaugeColors.GOOD),
            GaugeBand(StressLevel.REST.label, Double.POSITIVE_INFINITY, GaugeColors.REST),
        ),
    )

    val SLEEP_SCORE = GaugeScale(
        min = SCORE_MIN, max = SCORE_MAX,
        bands = listOf(
            GaugeBand(SleepQuality.POOR.label, SleepQuality.FAIR.minScore.toDouble(), GaugeColors.POOR),
            GaugeBand(SleepQuality.FAIR.label, SleepQuality.GOOD.minScore.toDouble(), GaugeColors.FAIR),
            GaugeBand(SleepQuality.GOOD.label, SleepQuality.EXCELLENT.minScore.toDouble(), GaugeColors.GOOD),
            GaugeBand(SleepQuality.EXCELLENT.label, Double.POSITIVE_INFINITY, GaugeColors.REST),
        ),
    )

    /** Acute:chronic load ratio; both ends of the optimal band are a warning. */
    val LOAD_RATIO = GaugeScale(
        min = LOAD_RATIO_MIN, max = LOAD_RATIO_MAX,
        bands = LoadBalance.entries.map { balance -> GaugeBand(balance.label, balance.maxRatio, loadColor(balance)) },
    )

    /** Planetary Kp; a level is reached once Kp rounds to its minimum, so each band ends half a step below the next. */
    val KP = GaugeScale(
        min = KP_MIN, max = KP_MAX,
        bands = GeomagneticLevel.entries.zipWithNext { level, next -> GaugeBand(level.label, next.minKp - KP_ROUNDING, kpColor(level)) } +
            GeomagneticLevel.entries.last().let { GaugeBand(it.label, Double.POSITIVE_INFINITY, kpColor(it)) },
    )

    fun kpColor(level: GeomagneticLevel): Color = when (level) {
        GeomagneticLevel.QUIET -> GaugeColors.REST
        GeomagneticLevel.UNSETTLED -> GaugeColors.GOOD
        GeomagneticLevel.ACTIVE -> GaugeColors.FAIR
        else -> GaugeColors.POOR
    }

    private fun loadColor(balance: LoadBalance): Color = when (balance) {
        LoadBalance.OPTIMAL -> GaugeColors.GOOD
        LoadBalance.LOW, LoadBalance.HIGH -> GaugeColors.FAIR
        LoadBalance.VERY_HIGH -> GaugeColors.POOR
    }

    /** Morning bright-light minutes against the daily goal. */
    fun morningLight(goalMinutes: Double) = GaugeScale(
        min = 0.0, max = goalMinutes * LIGHT_SCALE_GOALS,
        bands = listOf(
            GaugeBand("Below goal", goalMinutes, GaugeColors.FAIR),
            GaugeBand("Goal reached", Double.POSITIVE_INFINITY, GaugeColors.GOOD),
        ),
    )

    /** The 7-day RMSSD mean on the personal corridor: very low, low, balanced, above. */
    fun hrvCorridor(baseline: HrvBaseline): GaugeScale {
        val margin = baseline.high - baseline.veryLow
        return GaugeScale(
            min = baseline.veryLow - margin / 2, max = baseline.high + margin / 2,
            bands = listOf(
                GaugeBand(HrvStatusLevel.LOW.label, baseline.veryLow, GaugeColors.POOR),
                GaugeBand(HrvStatusLevel.UNBALANCED.label, baseline.low, GaugeColors.FAIR),
                GaugeBand(HrvStatusLevel.BALANCED.label, baseline.high, GaugeColors.GOOD),
                GaugeBand(HrvStatusLevel.UNBALANCED.label, Double.POSITIVE_INFINITY, GaugeColors.FAIR),
            ),
        )
    }

    val SLEEP_HOURS = GaugeScale(
        min = SLEEP_MIN_HOURS, max = SLEEP_MAX_HOURS,
        bands = listOf(
            GaugeBand("Short", SLEEP_SHORT_HOURS, GaugeColors.POOR),
            GaugeBand("Fair", SLEEP_FAIR_HOURS, GaugeColors.FAIR),
            GaugeBand("Good", Double.POSITIVE_INFINITY, GaugeColors.GOOD),
        ),
    )
}

private val BAR_HEIGHT = 8.dp
private val MARKER_RADIUS = 7.dp
private val MARKER_STROKE = 2.dp
private const val INACTIVE_ALPHA = 0.35f

/** Horizontal bar of [scale]'s bands with a marker at [value]; bands are dimmed while there is no value. */
@Composable
fun ColorScaleBar(scale: GaugeScale, value: Double?, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.height(MARKER_RADIUS * 2)) {
        val barHeight = BAR_HEIGHT.toPx()
        val top = (size.height - barHeight) / 2
        val corner = CornerRadius(barHeight / 2)
        var previous = scale.min
        scale.bands.forEach { band ->
            val from = scale.fraction(previous) * size.width
            val to = scale.fraction(band.upTo) * size.width
            if (to > from) {
                drawRoundRect(
                    color = band.color.copy(alpha = if (value == null) INACTIVE_ALPHA else 1f),
                    topLeft = Offset(from, top),
                    size = Size(to - from, barHeight),
                    cornerRadius = corner,
                )
            }
            previous = band.upTo
        }
        if (value != null) {
            val center = Offset(scale.fraction(value) * size.width, size.height / 2)
            drawCircle(Color.White, MARKER_RADIUS.toPx(), center)
            drawCircle(Color.Black, MARKER_RADIUS.toPx(), center, style = Stroke(MARKER_STROKE.toPx()))
        }
    }
}
