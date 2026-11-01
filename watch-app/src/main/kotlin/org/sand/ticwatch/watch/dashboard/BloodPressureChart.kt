package org.sand.ticwatch.watch.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import org.sand.ticwatch.watch.R
import org.sand.ticwatch.watch.bloodpressure.BloodPressureEntry

private val LINE_WIDTH = 1.dp
private val CUFF_WIDTH = 2.dp
private val POINT_RADIUS = 2.5.dp
private val LATEST_RADIUS = 3.5.dp
private val SELECTED_RADIUS = 4.5.dp
private val CUFF_RADIUS = 3.dp
private val GUIDE_WIDTH = 1.dp
private val GRID_WIDTH = 1.dp
private val VALUE_AXIS_WIDTH = 22.dp
private val AXIS_LABEL_GAP = 3.dp
private val AXIS_TEXT_SIZE = 9.sp
private const val VALUE_PADDING_MMHG = 5.0
private const val GRID_STEP_MMHG = 20
private const val GUIDE_DASH = 6f

/** Share of a ring dot's radius left as its background-colored center. */
private const val RING_HOLE_FRACTION = 0.45f

/** How far a curve's color is blended towards white, so the thin line reads lighter than its dots. */
private const val LINE_LIGHTEN_FRACTION = 0.45f

/** Opacity of the systolic-to-diastolic band fill; the grid is drawn over it and stays visible. */
private const val BAND_FILL_ALPHA = 0.5f
private const val GRID_ALPHA = 0.3f

/** Line colors of the two series. */
object BloodPressureChartColors {
    val SYSTOLIC = Color(0xFFEF5350)
    val DIASTOLIC = Color(0xFF42A5F5)

    /** Both values of a cuff reading, drawn as a marker instead of joining the wrist-estimate lines. */
    val CUFF = Color(0xFFFFB300)
}

/**
 * Systolic and diastolic [readings] (oldest first) over [start]..[end] on one shared scale with a 20 mmHg grid
 * labelled on the right: thin smooth curves through every reading, the band between them filled with a gradient of
 * their colors, the latest reading a ring. A tap selects the reading nearest in time ([onSelect]), which gets a guide
 * line and a larger ring. Mirrors the look of phone-app's `dashboard/BloodPressureChart.kt` (single panel here).
 */
@Composable
fun BloodPressureChart(
    readings: List<BloodPressureEntry>,
    start: Instant,
    end: Instant,
    selected: BloodPressureEntry?,
    onSelect: (BloodPressureEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (readings.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_readings_today), style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
        }
        return
    }
    val background = MaterialTheme.colors.background
    val axisColor = MaterialTheme.colors.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = axisColor, fontSize = AXIS_TEXT_SIZE)
    val spanMillis = Duration.between(start, end).toMillis().coerceAtLeast(1).toFloat()
    val low = readings.minOf { it.diastolic } - VALUE_PADDING_MMHG
    val high = readings.maxOf { it.systolic } + VALUE_PADDING_MMHG
    fun fraction(at: Instant): Float = (Duration.between(start, at).toMillis() / spanMillis).coerceIn(0f, 1f)
    val (cuffReadings, estimates) = readings.partition { it.fromCuff }

    Canvas(
        modifier.pointerInput(readings) {
            detectTapGestures { tap ->
                val plotWidth = size.width - VALUE_AXIS_WIDTH.toPx()
                onSelect(readings.minBy { abs(fraction(it.measuredAt) * plotWidth - tap.x) })
            }
        },
    ) {
        val plotWidth = size.width - VALUE_AXIS_WIDTH.toPx()
        fun x(at: Instant): Float = fraction(at) * plotWidth
        fun y(value: Number): Float = size.height * (1f - ((value.toDouble() - low) / (high - low)).toFloat())
        fun points(value: (BloodPressureEntry) -> Int) = estimates.map { Offset(x(it.measuredAt), y(value(it))) }
        val systolic = points { it.systolic }
        val diastolic = points { it.diastolic }

        if (estimates.isNotEmpty()) {
            val band = Path().apply {
                smoothThrough(systolic)
                smoothThrough(diastolic.asReversed())
                close()
            }
            val brush = Brush.verticalGradient(listOf(BloodPressureChartColors.SYSTOLIC, BloodPressureChartColors.DIASTOLIC))
            drawPath(band, brush, alpha = BAND_FILL_ALPHA)
        }
        drawValueGrid(low, high, plotWidth, axisColor.copy(alpha = GRID_ALPHA), textMeasurer, axisStyle, ::y)
        selected?.let { reading ->
            drawLine(
                axisColor, Offset(x(reading.measuredAt), 0f), Offset(x(reading.measuredAt), size.height), GUIDE_WIDTH.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_DASH, GUIDE_DASH)),
            )
        }
        val selectedIndex = estimates.indexOf(selected)
        listOf(BloodPressureChartColors.SYSTOLIC to systolic, BloodPressureChartColors.DIASTOLIC to diastolic)
            .forEach { (color, line) ->
                drawPath(
                    Path().apply { smoothThrough(line) },
                    lerp(color, Color.White, LINE_LIGHTEN_FRACTION),
                    style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                line.forEach { drawCircle(color, POINT_RADIUS.toPx(), it) }
                line.lastOrNull()?.let { drawRingDot(it, color, background, LATEST_RADIUS) }
                line.getOrNull(selectedIndex)?.let { drawRingDot(it, color, background, SELECTED_RADIUS) }
            }
        // Cuff readings stand apart from the wrist-estimate lines: a SYS-DIA bar, so the gap to the estimates shows.
        cuffReadings.forEach { reading ->
            val top = Offset(x(reading.measuredAt), y(reading.systolic))
            val bottom = Offset(x(reading.measuredAt), y(reading.diastolic))
            val radius = if (reading == selected) SELECTED_RADIUS else CUFF_RADIUS
            drawLine(BloodPressureChartColors.CUFF, top, bottom, CUFF_WIDTH.toPx(), cap = StrokeCap.Round)
            drawCircle(BloodPressureChartColors.CUFF, radius.toPx(), top)
            drawCircle(BloodPressureChartColors.CUFF, radius.toPx(), bottom)
        }
    }
}

/** Horizontal lines every [GRID_STEP_MMHG] inside [low]..[high], labelled right of the plot. */
private fun DrawScope.drawValueGrid(
    low: Double,
    high: Double,
    plotWidth: Float,
    color: Color,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    y: (Number) -> Float,
) {
    var value = (ceil(low / GRID_STEP_MMHG) * GRID_STEP_MMHG).toInt()
    while (value <= high) {
        val lineY = y(value)
        drawLine(color, Offset(0f, lineY), Offset(plotWidth, lineY), GRID_WIDTH.toPx())
        val label = textMeasurer.measure(value.toString(), style)
        val top = (lineY - label.size.height / 2f).coerceIn(0f, size.height - label.size.height)
        drawText(label, topLeft = Offset(plotWidth + AXIS_LABEL_GAP.toPx(), top))
        value += GRID_STEP_MMHG
    }
}

/** Continues the path through [points] (move to the first one on an empty path, line to it otherwise). */
private fun Path.smoothThrough(points: List<Offset>) {
    points.forEachIndexed { index, point ->
        if (index == 0) {
            if (isEmpty) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        } else {
            // Horizontal tangents at both ends: a smooth curve that never overshoots past the readings.
            val previous = points[index - 1]
            val midX = (previous.x + point.x) / 2
            cubicTo(midX, previous.y, midX, point.y, point.x, point.y)
        }
    }
}

/** Series-colored dot with a background-colored center, marking the latest or the selected reading. */
private fun DrawScope.drawRingDot(center: Offset, color: Color, background: Color, radius: Dp) {
    drawCircle(color, radius.toPx(), center)
    drawCircle(background, (radius * RING_HOLE_FRACTION).toPx(), center)
}
