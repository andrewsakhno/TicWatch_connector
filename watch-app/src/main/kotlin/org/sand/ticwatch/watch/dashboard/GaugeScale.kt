package org.sand.ticwatch.watch.dashboard

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

/** One colored stretch of a [GaugeScale], covering values up to [upTo]. */
data class GaugeBand(val label: String, val upTo: Double, val color: Color)

/** A value range split into colored [bands] (ascending [GaugeBand.upTo]), drawn by [ColorScaleBar]. */
class GaugeScale(val min: Double, val max: Double, val bands: List<GaugeBand>) {

    fun bandOf(value: Double): GaugeBand = bands.firstOrNull { value <= it.upTo } ?: bands.last()

    fun fraction(value: Double): Float = ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

private object GaugeColors {
    val GOOD = Color(0xFF66BB6A)
    val FAIR = Color(0xFFFFCA28)
    val POOR = Color(0xFFEF5350)
    val REST = Color(0xFF42A5F5)
}

object GaugeScales {
    /** Resting-ish heart rate as a stress proxy. */
    val HEART_RATE = GaugeScale(
        min = 40.0, max = 140.0,
        bands = listOf(
            GaugeBand("Low stress", 80.0, GaugeColors.GOOD),
            GaugeBand("Moderate stress", 100.0, GaugeColors.FAIR),
            GaugeBand("High stress", Double.POSITIVE_INFINITY, GaugeColors.POOR),
        ),
    )

    /** HRV readiness from RMSSD: higher is better. */
    val HRV_RMSSD = GaugeScale(
        min = 0.0, max = 100.0,
        bands = listOf(
            GaugeBand("Fatigue", 30.0, GaugeColors.POOR),
            GaugeBand("Normal", 50.0, GaugeColors.FAIR),
            GaugeBand("Excellent", Double.POSITIVE_INFINITY, GaugeColors.GOOD),
        ),
    )

    /** Phone-computed night score; band edges mirror phone-app's `SleepQuality`. */
    val SLEEP_SCORE = GaugeScale(
        min = 0.0, max = 100.0,
        bands = listOf(
            GaugeBand("Poor", 60.0, GaugeColors.POOR),
            GaugeBand("Fair", 80.0, GaugeColors.FAIR),
            GaugeBand("Good", 90.0, GaugeColors.GOOD),
            GaugeBand("Excellent", Double.POSITIVE_INFINITY, GaugeColors.GOOD),
        ),
    )

    /** Acute:chronic load ratio; band edges mirror phone-app's `LoadBalance`. */
    val LOAD_RATIO = GaugeScale(
        min = 0.0, max = 2.0,
        bands = listOf(
            GaugeBand("Detraining", 0.8, GaugeColors.FAIR),
            GaugeBand("Optimal", 1.3, GaugeColors.GOOD),
            GaugeBand("High", 1.5, GaugeColors.FAIR),
            GaugeBand("Overreaching", Double.POSITIVE_INFINITY, GaugeColors.POOR),
        ),
    )

    /** Planetary Kp; band edges mirror phone-app's `GeomagneticLevel` (a level counts once Kp rounds to it). */
    val KP = GaugeScale(
        min = 0.0, max = 9.0,
        bands = listOf(
            GaugeBand("Quiet", 2.5, GaugeColors.REST),
            GaugeBand("Unsettled", 3.5, GaugeColors.GOOD),
            GaugeBand("Active", 4.5, GaugeColors.FAIR),
            GaugeBand("Storm", Double.POSITIVE_INFINITY, GaugeColors.POOR),
        ),
    )

    val SPO2 = GaugeScale(
        min = 85.0, max = 100.0,
        bands = listOf(
            GaugeBand("Low", 90.0, GaugeColors.POOR),
            GaugeBand("Fair", 94.0, GaugeColors.FAIR),
            GaugeBand("Normal", Double.POSITIVE_INFINITY, GaugeColors.GOOD),
        ),
    )
}

private val BAR_HEIGHT = 8.dp
private val MARKER_RADIUS = 6.dp
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
