package org.sand.ticwatch.watch.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import org.sand.ticwatch.watch.bloodpressure.BloodPressureEntry
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

private val LINE_WIDTH = 2.dp
private val POINT_RADIUS = 2.dp
private val SELECTED_RADIUS = 4.dp
private val CUFF_RADIUS = 3.dp
private val GUIDE_WIDTH = 1.dp
private const val VALUE_PADDING_MMHG = 5.0
private const val GUIDE_DASH = 6f

/** A gap this long between readings breaks the line: the wearer wasn't resting (or measuring) in between. */
private val MAX_LINE_GAP: Duration = Duration.ofMinutes(20)

/** Line colors of the two series. */
object BloodPressureChartColors {
    val SYSTOLIC = Color(0xFFEF5350)
    val DIASTOLIC = Color(0xFF42A5F5)

    /** Both values of a cuff reading, drawn as a marker instead of joining the wrist-estimate lines. */
    val CUFF = Color(0xFFFFB300)
}

/**
 * Systolic and diastolic [readings] (oldest first) over [start]..[end], scaled to their own range; a tap selects
 * the reading nearest in time ([onSelect]), which gets a guide line and larger dots. Mirrors phone-app's
 * `dashboard/BloodPressureChart.kt`.
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
            Text("No readings today", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
        }
        return
    }
    val spanMillis = Duration.between(start, end).toMillis().coerceAtLeast(1).toFloat()
    val low = readings.minOf { it.diastolic } - VALUE_PADDING_MMHG
    val high = readings.maxOf { it.systolic } + VALUE_PADDING_MMHG
    fun fraction(at: Instant): Float = (Duration.between(start, at).toMillis() / spanMillis).coerceIn(0f, 1f)

    Canvas(
        modifier.pointerInput(readings) {
            detectTapGestures { tap ->
                onSelect(readings.minBy { abs(fraction(it.measuredAt) * size.width - tap.x) })
            }
        },
    ) {
        fun x(at: Instant): Float = fraction(at) * size.width
        fun y(value: Int): Float = size.height * (1f - ((value - low) / (high - low)).toFloat())

        selected?.let { reading ->
            drawLine(
                Color.Gray, Offset(x(reading.measuredAt), 0f), Offset(x(reading.measuredAt), size.height), GUIDE_WIDTH.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_DASH, GUIDE_DASH)),
            )
        }
        val (cuffReadings, estimates) = readings.partition { it.fromCuff }
        listOf<Pair<Color, (BloodPressureEntry) -> Int>>(
            BloodPressureChartColors.SYSTOLIC to { it.systolic },
            BloodPressureChartColors.DIASTOLIC to { it.diastolic },
        ).forEach { (color, value) ->
            val line = Path()
            estimates.forEachIndexed { index, reading ->
                val point = Offset(x(reading.measuredAt), y(value(reading)))
                val continues = index > 0 && Duration.between(estimates[index - 1].measuredAt, reading.measuredAt) <= MAX_LINE_GAP
                if (continues) line.lineTo(point.x, point.y) else line.moveTo(point.x, point.y)
                val radius = if (reading == selected) SELECTED_RADIUS else POINT_RADIUS
                drawCircle(color, radius.toPx(), point)
            }
            drawPath(line, color, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Cuff readings stand apart from the wrist-estimate lines: a SYS-DIA bar, so the gap to the estimates shows.
        cuffReadings.forEach { reading ->
            val top = Offset(x(reading.measuredAt), y(reading.systolic))
            val bottom = Offset(x(reading.measuredAt), y(reading.diastolic))
            val radius = if (reading == selected) SELECTED_RADIUS else CUFF_RADIUS
            drawLine(BloodPressureChartColors.CUFF, top, bottom, LINE_WIDTH.toPx(), cap = StrokeCap.Round)
            drawCircle(BloodPressureChartColors.CUFF, radius.toPx(), top)
            drawCircle(BloodPressureChartColors.CUFF, radius.toPx(), bottom)
        }
    }
}
