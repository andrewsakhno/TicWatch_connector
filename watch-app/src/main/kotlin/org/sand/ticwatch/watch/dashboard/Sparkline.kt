package org.sand.ticwatch.watch.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import org.sand.ticwatch.watch.sensors.TimedReading
import java.time.Duration
import java.time.Instant

private val LINE_WIDTH = 2.dp
private val LAST_POINT_RADIUS = 3.dp
private const val FILL_ALPHA = 0.35f
private const val VERTICAL_PADDING_FRACTION = 0.1f
private const val MIN_POINTS = 2

/**
 * Trend of [points] over the last [window] (right edge = now), scaled to their own min-max, with a
 * soft fill below the line and a dot on the newest value.
 */
@Composable
fun Sparkline(points: List<TimedReading>, color: Color, window: Duration, modifier: Modifier = Modifier) {
    if (points.size < MIN_POINTS) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Collecting trend…", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
        }
        return
    }
    val end = maxOf(Instant.now(), points.last().at)
    val start = end.minus(window)
    val low = points.minOf { it.value }
    val high = points.maxOf { it.value }
    Canvas(modifier) {
        val padding = size.height * VERTICAL_PADDING_FRACTION
        val usable = size.height - 2 * padding
        fun x(at: Instant): Float =
            (Duration.between(start, at).toMillis().toFloat() / window.toMillis()).coerceIn(0f, 1f) * size.width
        fun y(value: Double): Float =
            if (high == low) size.height / 2 else padding + usable * (1f - ((value - low) / (high - low)).toFloat())

        val line = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) line.moveTo(x(point.at), y(point.value)) else line.lineTo(x(point.at), y(point.value))
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(points.last().at), size.height)
            lineTo(x(points.first().at), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = FILL_ALPHA), Color.Transparent)))
        drawPath(line, color, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = points.last()
        drawCircle(color, LAST_POINT_RADIUS.toPx(), Offset(x(last.at), y(last.value)))
    }
}
