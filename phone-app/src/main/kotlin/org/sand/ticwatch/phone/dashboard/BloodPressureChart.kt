package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.health.BloodPressureCategory
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.SampleSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val LINE_WIDTH = 2.dp
private val POINT_RADIUS = 2.5.dp
private val SELECTED_RADIUS = 5.dp
private val CUFF_RADIUS = 3.5.dp
private val GUIDE_WIDTH = 1.dp
private val CHART_HEIGHT = 140.dp
private val LEGEND_SPACING = 12.dp
private val ROW_SPACING = 4.dp
private const val VALUE_PADDING_MMHG = 5.0
private const val GUIDE_DASH = 8f
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** A gap this long between readings breaks the line: the wearer wasn't resting (or measuring) in between. */
private val MAX_LINE_GAP: Duration = Duration.ofMinutes(20)

/** Line colors of the two series. */
object BloodPressureChartColors {
    val SYSTOLIC = Color(0xFFEF5350)
    val DIASTOLIC = Color(0xFF42A5F5)

    /** Both values of a cuff reading, drawn as a marker instead of joining the wrist-estimate lines. */
    val CUFF = Color(0xFFFFB300)

    /** Vertical line at a logged medication intake. */
    val MEDICATION = Color(0xFF26A69A)
}

private val NORMAL_COLOR = Color(0xFF9CCC65)
private val GRADE_1_COLOR = Color(0xFFFFA726)
private val GRADE_3_COLOR = Color(0xFFB71C1C)

/** Color of a reading's ESC/ESH category, shared by the Blood Pressure and Metrics tabs. */
fun categoryColor(category: BloodPressureCategory): Color = when (category) {
    BloodPressureCategory.OPTIMAL -> GaugeColors.GOOD
    BloodPressureCategory.NORMAL -> NORMAL_COLOR
    BloodPressureCategory.HIGH_NORMAL -> GaugeColors.FAIR
    BloodPressureCategory.HYPERTENSION_GRADE_1 -> GRADE_1_COLOR
    BloodPressureCategory.HYPERTENSION_GRADE_2 -> GaugeColors.POOR
    BloodPressureCategory.HYPERTENSION_GRADE_3 -> GRADE_3_COLOR
}

/** "Watch estimate, pulse 64" style origin line of a reading. */
fun readingSourceText(reading: BloodPressureReading): String {
    val origin = if (reading.source == SampleSource.WATCH) "${reading.source.label} estimate" else reading.source.label
    return reading.pulse?.let { "$origin, pulse ${it.roundToInt()}" } ?: origin
}

/**
 * [BloodPressureChart] of [readings] (oldest first) over [start]..[end] with a legend; tapping it shows the nearest
 * reading's details below, followed by [selectedActions] (e.g. Delete) when given. [markers] (medication intakes)
 * are drawn as vertical lines.
 */
@Composable
fun BloodPressureDayChart(
    readings: List<BloodPressureReading>,
    start: Instant,
    end: Instant,
    selectedActions: (@Composable (BloodPressureReading) -> Unit)? = null,
    markers: List<Instant> = emptyList(),
) {
    var selected by remember(readings) { mutableStateOf<BloodPressureReading?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING)) {
        BloodPressureChart(readings, start, end, selected, { selected = it }, Modifier.fillMaxWidth().height(CHART_HEIGHT), markers)
        Row(horizontalArrangement = Arrangement.spacedBy(LEGEND_SPACING)) {
            Text(text = "Systolic", style = MaterialTheme.typography.labelSmall, color = BloodPressureChartColors.SYSTOLIC)
            Text(text = "Diastolic", style = MaterialTheme.typography.labelSmall, color = BloodPressureChartColors.DIASTOLIC)
            Text(text = "Cuff", style = MaterialTheme.typography.labelSmall, color = BloodPressureChartColors.CUFF)
            if (markers.isNotEmpty()) {
                Text(text = "Medication", style = MaterialTheme.typography.labelSmall, color = BloodPressureChartColors.MEDICATION)
            }
            Text(
                text = "${TIME_FORMAT.format(start)} - ${TIME_FORMAT.format(end)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val reading = selected
        if (reading == null) {
            if (readings.isNotEmpty()) Text(text = "Tap the chart for a reading.", style = MaterialTheme.typography.bodySmall)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${reading.systolic.roundToInt()}/${reading.diastolic.roundToInt()}",
                    color = categoryColor(reading.category),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Column(modifier = Modifier.weight(2f)) {
                    Text(
                        text = "${TIME_FORMAT.format(reading.measuredAt)} · ${reading.category.label}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(text = readingSourceText(reading), style = MaterialTheme.typography.bodySmall)
                }
                selectedActions?.invoke(reading)
            }
        }
    }
}

/**
 * Systolic and diastolic [readings] (oldest first) over [start]..[end], scaled to their own range; a tap selects
 * the reading nearest in time ([onSelect]), which gets a guide line and larger dots. Mirrors watch-app's
 * `dashboard/BloodPressureChart.kt`.
 */
@Composable
fun BloodPressureChart(
    readings: List<BloodPressureReading>,
    start: Instant,
    end: Instant,
    selected: BloodPressureReading?,
    onSelect: (BloodPressureReading) -> Unit,
    modifier: Modifier = Modifier,
    markers: List<Instant> = emptyList(),
) {
    if (readings.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No readings in this period",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        fun y(value: Double): Float = size.height * (1f - ((value - low) / (high - low)).toFloat())

        markers.filter { !it.isBefore(start) && !it.isAfter(end) }.forEach { at ->
            drawLine(BloodPressureChartColors.MEDICATION, Offset(x(at), 0f), Offset(x(at), size.height), LINE_WIDTH.toPx())
        }
        selected?.let { reading ->
            drawLine(
                Color.Gray, Offset(x(reading.measuredAt), 0f), Offset(x(reading.measuredAt), size.height), GUIDE_WIDTH.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_DASH, GUIDE_DASH)),
            )
        }
        val (cuffReadings, estimates) = readings.partition { it.source == SampleSource.BLOOD_PRESSURE_CUFF }
        listOf<Pair<Color, (BloodPressureReading) -> Double>>(
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
