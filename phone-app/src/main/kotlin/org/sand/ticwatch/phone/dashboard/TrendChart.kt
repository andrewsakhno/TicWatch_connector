package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.insights.Trend

/** One line of a [TrendChart]. */
data class TrendSeries(val label: String, val trend: Trend, val color: Color)

private val LINE_WIDTH = 2.dp
private val POINT_RADIUS = 3.dp
private val GRID_WIDTH = 1.dp
private val AXIS_GUTTER = 36.dp
private val LEGEND_SWATCH = 10.dp
private val AXIS_TEXT_SIZE = 10.sp
private const val VERTICAL_PADDING_FRACTION = 0.1f
private const val GRID_ALPHA = 0.3f

/**
 * Several [series] over their shared start..end on one value axis (min..max of all points, labelled at the left),
 * a dot per value since the points are sparse, the time span below and a legend when there is more than one line.
 */
@Composable
fun TrendChart(series: List<TrendSeries>, formatTime: (Instant) -> String, modifier: Modifier = Modifier) {
    val points = series.flatMap { it.trend.points }
    if (points.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.not_enough_history), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val start = series.minOf { it.trend.start }
    val end = series.maxOf { it.trend.end }
    val low = points.minOf { it.value }
    val high = points.maxOf { it.value }
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = AXIS_TEXT_SIZE, color = axisColor)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier) {
            val gutter = AXIS_GUTTER.toPx()
            val padding = size.height * VERTICAL_PADDING_FRACTION
            val usable = size.height - 2 * padding
            val width = size.width - gutter
            val spanMillis = Duration.between(start, end).toMillis().toFloat()
            fun x(at: Instant): Float =
                if (spanMillis <= 0f) gutter + width / 2 else gutter + Duration.between(start, at).toMillis() / spanMillis * width
            fun y(value: Double): Float =
                if (high == low) size.height / 2 else padding + usable * (1f - ((value - low) / (high - low)).toFloat())

            listOf(high, low).distinct().forEach { value ->
                drawLine(axisColor.copy(alpha = GRID_ALPHA), Offset(gutter, y(value)), Offset(size.width, y(value)), GRID_WIDTH.toPx())
                val label = measurer.measure(value.roundToInt().toString(), axisStyle)
                drawText(label, topLeft = Offset(0f, y(value) - label.size.height / 2))
            }
            series.forEach { line ->
                val linePoints = line.trend.points
                val path = Path()
                linePoints.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(x(point.at), y(point.value)) else path.lineTo(x(point.at), y(point.value))
                }
                drawPath(path, line.color, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                linePoints.forEach { drawCircle(line.color, POINT_RADIUS.toPx(), Offset(x(it.at), y(it.value))) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = AXIS_GUTTER)) {
            Text(text = formatTime(start), style = MaterialTheme.typography.labelSmall, color = axisColor, modifier = Modifier.weight(1f))
            if (end != start) Text(text = formatTime(end), style = MaterialTheme.typography.labelSmall, color = axisColor)
        }
        if (series.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(start = AXIS_GUTTER)) {
                series.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(LEGEND_SWATCH)) { drawCircle(line.color) }
                        Text(text = line.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
