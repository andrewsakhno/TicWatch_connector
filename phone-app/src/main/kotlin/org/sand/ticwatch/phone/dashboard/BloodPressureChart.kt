package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.health.BloodPressureCategory
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.text.UiText
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.text.inCurrentLocale
import org.sand.ticwatch.phone.text.uiText

private val LINE_WIDTH = 1.5.dp
private val MARKER_WIDTH = 2.5.dp

/** How far a curve's color is blended towards white, so the thin line reads lighter than its dots. */
private const val LINE_LIGHTEN_FRACTION = 0.45f

/** Length of a curve segment's control handles as a fraction of its width: small, so only the corners get rounded. */
private const val CORNER_ROUNDING = 0.25f

/** Opacity of the systolic-to-diastolic band fill; the grid is drawn over it and stays visible. */
private const val BAND_FILL_ALPHA = 0.5f
private val POINT_RADIUS = 3.5.dp
private val SELECTED_RADIUS = 6.5.dp
private val LATEST_RADIUS = 5.dp

/** Share of a ring dot's radius left as its surface-colored center. */
private const val RING_HOLE_FRACTION = 0.45f
private val CUFF_RADIUS = 4.5.dp
private val CUFF_OUTLINE = 1.5.dp
private val GUIDE_WIDTH = 1.dp
private val GRID_WIDTH = 1.dp
private val SERIES_HEIGHT = 80.dp
private val SERIES_GAP = 22.dp
/** Two label rows under the plot: hour of day, then the date at each local midnight. */
private val TIME_AXIS_HEIGHT = 30.dp
private val VALUE_AXIS_WIDTH = 34.dp
private val AXIS_LABEL_GAP = 6.dp

/** Room above the top grid line for half of its value label. */
private val VALUE_LABEL_INSET = 7.dp
private val CHART_HEIGHT = VALUE_LABEL_INSET + SERIES_HEIGHT * 2 + SERIES_GAP + TIME_AXIS_HEIGHT
private val AXIS_TEXT_SIZE = 10.sp
private val LEGEND_SPACING = 12.dp
private val ROW_SPACING = 4.dp
private val UNIT_SPACING = 4.dp
private const val VALUE_PADDING_MMHG = 5.0
private const val GRID_STEP_MMHG = 20
private const val MIN_GRID_STEPS = 2
/** Fixed time scale of the scrollable plot: a 3-day window is about 1440 dp wide. */
private val HOUR_WIDTH = 20.dp
private const val MILLIS_PER_HOUR = 3_600_000f
private const val HOUR_LABEL_STEP_HOURS = 6
private const val MIDNIGHT_HOUR = 0
private val DAY_SEPARATOR_WIDTH = 1.dp
private const val DAY_SEPARATOR_ALPHA = 0.6f
private const val GUIDE_DASH = 8f
private const val NO_VALUE = "--"
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd")
private val READING_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE dd HH:mm").withZone(ZoneId.systemDefault())

/** Chart colors, taken from the app theme so the chart matches the other cards. */
private class BloodPressureChartColors(
    val systolic: Color,
    val diastolic: Color,
    /** The dots of a cuff reading's two values. */
    val cuff: Color,
    /** Vertical line at a logged medication intake. */
    val medication: Color,
    val grid: Color,
    val axisText: Color,
    /** Fill behind markers and inside the latest/selected dots: the card surface. */
    val background: Color,
)

@Composable
private fun bloodPressureChartColors(): BloodPressureChartColors = MaterialTheme.colorScheme.let {
    BloodPressureChartColors(
        systolic = it.primary,
        diastolic = it.tertiary,
        cuff = it.secondary,
        medication = it.outline,
        grid = it.outlineVariant,
        axisText = it.onSurfaceVariant,
        background = it.surface,
    )
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

/** "Watch estimate, pulse 64" style origin line of a reading; also sent to the watch's blood pressure page. */
fun readingSourceText(reading: BloodPressureReading): UiText {
    val source = uiText(reading.source.label)
    val origin = if (reading.source == SampleSource.WATCH) uiText(R.string.source_estimate, source) else source
    return reading.pulse?.let { uiText(R.string.source_with_pulse, origin, it.roundToInt()) } ?: origin
}

/**
 * The latest (or tapped) reading in large digits above [BloodPressureChart] of [readings] (oldest
 * first) over [start]..[end]; tapping the chart shows the nearest reading's details below, followed by
 * [selectedActions] (e.g. Delete) when given. [markers] (medication intakes) are drawn as vertical lines.
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
    val shown = selected ?: readings.lastOrNull()
    val colors = bloodPressureChartColors()
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING)) {
        LargeValueRow(stringResource(R.string.chart_systolic), shown?.systolic, colors.systolic, colors.axisText)
        LargeValueRow(stringResource(R.string.chart_diastolic), shown?.diastolic, colors.diastolic, colors.axisText)
        BloodPressureChart(readings, start, end, selected, { selected = it }, Modifier.fillMaxWidth().height(CHART_HEIGHT), markers)
        Row(horizontalArrangement = Arrangement.spacedBy(LEGEND_SPACING)) {
            if (readings.any { it.source == SampleSource.BLOOD_PRESSURE_CUFF }) {
                Text(text = stringResource(R.string.chart_cuff), style = MaterialTheme.typography.labelSmall, color = colors.cuff)
            }
            if (markers.isNotEmpty()) {
                Text(text = stringResource(R.string.chart_medication), style = MaterialTheme.typography.labelSmall, color = colors.medication)
            }
        }
        val reading = selected
        if (reading == null) {
            if (readings.isNotEmpty()) Text(text = stringResource(R.string.chart_tap_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.bp_compact, reading.systolic.roundToInt(), reading.diastolic.roundToInt()),
                    color = categoryColor(reading.category),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Column(modifier = Modifier.weight(2f)) {
                    Text(
                        text = stringResource(R.string.reading_time_category, READING_TIME_FORMAT.inCurrentLocale().format(reading.measuredAt), stringResource(reading.category.label)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(text = readingSourceText(reading).asString(), style = MaterialTheme.typography.bodySmall)
                }
                selectedActions?.invoke(reading)
            }
        }
    }
}

/** "SYSTOLIC .... 162 mmHg" header row: caption on the left, the value in large light digits on the right. */
@Composable
private fun LargeValueRow(label: String, value: Double?, color: Color, labelColor: Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Text(
            text = value?.roundToInt()?.toString() ?: NO_VALUE,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = color,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = stringResource(R.string.mmhg),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.alignByBaseline().padding(start = UNIT_SPACING),
        )
    }
}

/** One plotted value of a reading and its color. */
private class ChartSeries(val color: Color, val value: (BloodPressureReading) -> Double)

/** Vertical scale of one series panel: snapped to [GRID_STEP_MMHG] around the series' own values. */
private class SeriesScale(values: List<Double>, private val top: Float, private val height: Float) {
    private val low = floor((values.min() - VALUE_PADDING_MMHG) / GRID_STEP_MMHG) * GRID_STEP_MMHG
    private val high = max(
        ceil((values.max() + VALUE_PADDING_MMHG) / GRID_STEP_MMHG) * GRID_STEP_MMHG,
        low + MIN_GRID_STEPS * GRID_STEP_MMHG,
    )
    val gridValues: List<Int> = (low.toInt()..high.toInt() step GRID_STEP_MMHG).toList()

    fun y(value: Double): Float = top + height * (1f - ((value - low) / (high - low)).toFloat())
}

/** Panel scales of [series] in this canvas; the scrolling plot and the sticky value axis share them. */
private fun DrawScope.seriesScales(series: List<ChartSeries>, readings: List<BloodPressureReading>): List<SeriesScale> {
    val plotTop = VALUE_LABEL_INSET.toPx()
    val plotBottom = size.height - TIME_AXIS_HEIGHT.toPx()
    val seriesHeight = (plotBottom - plotTop - SERIES_GAP.toPx()) / series.size
    return series.mapIndexed { index, it ->
        SeriesScale(readings.map(it.value), plotTop + index * (seriesHeight + SERIES_GAP.toPx()), seriesHeight)
    }
}

/**
 * Systolic (top) and diastolic (bottom) [readings] (oldest first) over [start]..[end], each in its own panel with a
 * 20 mmHg grid and hours/dates below; every reading is a dot, the latest estimate a ring. The slightly rounded curve and
 * the band pass through every reading. The plot has a
 * fixed [HOUR_WIDTH] time scale and scrolls horizontally, opening (and re-opening on new readings) at [end] on the
 * right; the value labels stay pinned to the right edge. A tap selects the reading nearest in time ([onSelect]),
 * which gets a guide line and larger dots. The watch-app's `dashboard/BloodPressureChart.kt` keeps the older
 * single-panel look.
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
            Text(stringResource(R.string.chart_no_readings), style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val colors = bloodPressureChartColors()
    val textMeasurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = colors.axisText, fontSize = AXIS_TEXT_SIZE)
    val spanMillis = Duration.between(start, end).toMillis().coerceAtLeast(1).toFloat()
    fun fraction(at: Instant): Float = (Duration.between(start, at).toMillis() / spanMillis).coerceIn(0f, 1f)
    val (cuffReadings, estimates) = readings.partition { it.source == SampleSource.BLOOD_PRESSURE_CUFF }
    val series = listOf(
        ChartSeries(colors.systolic) { it.systolic },
        ChartSeries(colors.diastolic) { it.diastolic },
    )
    val scrollState = rememberScrollState()
    // maxValue changes once the canvas is measured, so this also lands on the right edge on the first load.
    // Keyed by the newest reading, not the count: the sliding window can drop an old reading as a new one arrives.
    LaunchedEffect(readings.last().measuredAt, scrollState.maxValue) { scrollState.scrollTo(scrollState.maxValue) }

    Row(modifier) {
        // Measured outside the scroll, so maxWidth is the finite viewport width, not an unbounded one.
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            // A short window still fills the viewport rather than leaving part of it empty.
            val plotWidth = max(maxWidth.value, HOUR_WIDTH.value * spanMillis / MILLIS_PER_HOUR).dp
            Canvas(
                Modifier.horizontalScroll(scrollState).width(plotWidth).fillMaxHeight().pointerInput(readings) {
                    // tap.x is relative to the whole scrollable canvas, i.e. to the full time scale.
                    detectTapGestures { tap -> onSelect(readings.minBy { abs(fraction(it.measuredAt) * size.width - tap.x) }) }
                },
            ) {
                val width = size.width
                val plotBottom = size.height - TIME_AXIS_HEIGHT.toPx()
                fun x(at: Instant): Float = fraction(at) * width
                val scales = seriesScales(series, readings)

                drawPressureBand(series, scales, readings, VALUE_LABEL_INSET.toPx(), plotBottom, ::x)
                scales.forEach { drawValueGrid(it, width, colors.grid) }
                drawTimeAxis(start, end, width, plotBottom, ::x, colors.axisText, textMeasurer, axisStyle)
                markers.filter { !it.isBefore(start) && !it.isAfter(end) }.forEach { at ->
                    drawLine(colors.medication, Offset(x(at), 0f), Offset(x(at), plotBottom), MARKER_WIDTH.toPx())
                }
                selected?.let { reading ->
                    drawLine(
                        colors.axisText, Offset(x(reading.measuredAt), 0f), Offset(x(reading.measuredAt), plotBottom), GUIDE_WIDTH.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(GUIDE_DASH, GUIDE_DASH)),
                    )
                }
                series.zip(scales).forEach { (line, scale) ->
                    drawSeriesLine(line, scale, readings, estimates, selected, colors.background, ::x)
                    cuffReadings.forEach { reading ->
                        val point = Offset(x(reading.measuredAt), scale.y(line.value(reading)))
                        val radius = if (reading == selected) SELECTED_RADIUS else CUFF_RADIUS
                        drawCircle(colors.background, (radius + CUFF_OUTLINE).toPx(), point)
                        drawCircle(colors.cuff, radius.toPx(), point)
                    }
                }
            }
        }
        Canvas(Modifier.width(VALUE_AXIS_WIDTH).fillMaxHeight()) {
            seriesScales(series, readings).forEach { drawValueLabels(it, textMeasurer, axisStyle) }
        }
    }
}

private fun DrawScope.drawValueGrid(scale: SeriesScale, plotWidth: Float, color: Color) {
    scale.gridValues.forEach { value ->
        val y = scale.y(value.toDouble())
        drawLine(color, Offset(0f, y), Offset(plotWidth, y), GRID_WIDTH.toPx())
    }
}

/** The grid values of [scale], drawn in the sticky axis canvas next to the scrolling plot. */
private fun DrawScope.drawValueLabels(scale: SeriesScale, textMeasurer: TextMeasurer, style: TextStyle) {
    scale.gridValues.forEach { value ->
        val label = textMeasurer.measure(value.toString(), style)
        drawText(label, topLeft = Offset(AXIS_LABEL_GAP.toPx(), scale.y(value.toDouble()) - label.size.height / 2f))
    }
}

/**
 * Local "HH:mm" labels every [HOUR_LABEL_STEP_HOURS] hours under the plot; each local midnight also gets a day
 * separator line and its "EEE dd" date in the second row (the first, partial day is dated at the left edge).
 */
private fun DrawScope.drawTimeAxis(
    start: Instant,
    end: Instant,
    plotWidth: Float,
    top: Float,
    x: (Instant) -> Float,
    color: Color,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    val gap = AXIS_LABEL_GAP.toPx()
    val hourRowTop = top + gap / 2
    val dateRowTop = hourRowTop + textMeasurer.measure(TIME_FORMAT.format(start), style).size.height
    val dateStyle = style.copy(fontWeight = FontWeight.Bold)
    fun drawDate(day: ZonedDateTime, left: Float) {
        val label = textMeasurer.measure(DAY_FORMAT.inCurrentLocale().format(day), dateStyle)
        drawText(label, topLeft = Offset(left.coerceIn(0f, max(0f, plotWidth - label.size.width)), dateRowTop))
    }
    val first = start.atZone(ZoneId.systemDefault())
    val firstDateWidth = textMeasurer.measure(DAY_FORMAT.inCurrentLocale().format(first), dateStyle).size.width
    if (x(first.truncatedTo(ChronoUnit.DAYS).plusDays(1).toInstant()) > firstDateWidth + 2 * gap) drawDate(first, 0f)

    var hour = first.truncatedTo(ChronoUnit.HOURS).plusHours(1)
    while (!hour.toInstant().isAfter(end)) {
        if (hour.hour % HOUR_LABEL_STEP_HOURS == 0) {
            val at = x(hour.toInstant())
            val label = textMeasurer.measure(TIME_FORMAT.format(hour), style)
            drawText(label, topLeft = Offset((at - label.size.width / 2f).coerceIn(0f, max(0f, plotWidth - label.size.width)), hourRowTop))
            if (hour.hour == MIDNIGHT_HOUR) {
                drawLine(color.copy(alpha = DAY_SEPARATOR_ALPHA), Offset(at, 0f), Offset(at, top), DAY_SEPARATOR_WIDTH.toPx())
                drawDate(hour, at + gap)
            }
        }
        hour = hour.plusHours(1)
    }
}

private fun seriesPoints(
    series: ChartSeries,
    scale: SeriesScale,
    readings: List<BloodPressureReading>,
    x: (Instant) -> Float,
): List<Offset> = readings.map { Offset(x(it.measuredAt), scale.y(series.value(it))) }

/** Slope of the curve at [points]`[index]`: the line between its neighbours (or the only neighbour at either end). */
private fun slopeAt(points: List<Offset>, index: Int): Float {
    val before = points[(index - 1).coerceAtLeast(0)]
    val after = points[(index + 1).coerceAtMost(points.lastIndex)]
    val run = after.x - before.x
    return if (run == 0f) 0f else (after.y - before.y) / run
}

/**
 * Continues the path through all [points] (move to the first one on an empty path, line to it otherwise), each segment
 * a nearly straight cubic that only rounds the corners. Control points stay inside their segment's x range, so the
 * curve never runs backwards in time, however unevenly the readings are spaced.
 */
private fun Path.roundedThrough(points: List<Offset>) {
    points.forEachIndexed { index, point ->
        if (index == 0) {
            if (isEmpty) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        } else {
            val previous = points[index - 1]
            val handle = (point.x - previous.x) * CORNER_ROUNDING
            cubicTo(
                previous.x + handle, previous.y + slopeAt(points, index - 1) * handle,
                point.x - handle, point.y - slopeAt(points, index) * handle,
                point.x, point.y,
            )
        }
    }
}

/** Fills the space between the systolic (top) and diastolic (bottom) curves with a gradient of their colors. */
private fun DrawScope.drawPressureBand(
    series: List<ChartSeries>,
    scales: List<SeriesScale>,
    readings: List<BloodPressureReading>,
    top: Float,
    bottom: Float,
    x: (Instant) -> Float,
) {
    if (readings.isEmpty()) return
    val upper = seriesPoints(series.first(), scales.first(), readings, x)
    val lower = seriesPoints(series.last(), scales.last(), readings, x)
    val band = Path().apply {
        roundedThrough(upper)
        roundedThrough(lower.asReversed())
        close()
    }
    val brush = Brush.verticalGradient(listOf(series.first().color, series.last().color), startY = top, endY = bottom)
    drawPath(band, brush, alpha = BAND_FILL_ALPHA)
}

private fun DrawScope.drawSeriesLine(
    series: ChartSeries,
    scale: SeriesScale,
    readings: List<BloodPressureReading>,
    estimates: List<BloodPressureReading>,
    selected: BloodPressureReading?,
    background: Color,
    x: (Instant) -> Float,
) {
    fun point(reading: BloodPressureReading) = Offset(x(reading.measuredAt), scale.y(series.value(reading)))
    val line = Path().apply { roundedThrough(seriesPoints(series, scale, readings, x)) }
    val lineColor = lerp(series.color, Color.White, LINE_LIGHTEN_FRACTION)
    drawPath(line, lineColor, style = Stroke(LINE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    estimates.forEach { drawCircle(series.color, POINT_RADIUS.toPx(), point(it)) }
    estimates.lastOrNull()?.let { drawRingDot(point(it), series.color, background, LATEST_RADIUS) }
    selected?.takeIf { it in estimates }?.let { drawRingDot(point(it), series.color, background, SELECTED_RADIUS) }
}

/** Series-colored dot with a surface-colored center, marking the latest or the selected reading. */
private fun DrawScope.drawRingDot(center: Offset, color: Color, background: Color, radius: Dp) {
    drawCircle(color, radius.toPx(), center)
    drawCircle(background, (radius * RING_HOLE_FRACTION).toPx(), center)
}
