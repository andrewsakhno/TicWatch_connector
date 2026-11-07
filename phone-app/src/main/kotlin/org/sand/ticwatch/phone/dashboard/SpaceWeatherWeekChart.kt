package org.sand.ticwatch.phone.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.spaceweather.GeomagneticLevel
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherDay
import org.sand.ticwatch.phone.text.inCurrentLocale

private val CHART_HEIGHT = 140.dp
private val BAR_CORNER = 4.dp
private val GRID_STROKE = 1.dp
private const val BAR_WIDTH_SHARE = 0.6f
private const val COLOR_TRANSITION_KP = 0.6
private const val FORECAST_ALPHA = 0.6f
private const val GRID_ALPHA = 0.5f
private const val SELECTION_ALPHA = 0.12f
private const val KP_ROUNDING = 0.5
private val GRID_DASH = floatArrayOf(8f, 8f)
private val WEEKDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")
private val DETAIL_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE dd.MM")
private const val MISSING = "-"

/**
 * One column per day: a bar as tall as the day's max Kp on the [GaugeScales.KP] scale, painted with that scale's
 * colors blending into each other (so a Kp 5 bar runs blue -> green -> yellow -> red), forecast days faded; the
 * dashed line is where storms (G1) begin. Under each bar: Kp, weekday, date and the solar radio flux. Tapping a
 * column (today at first) shows that day's level and details above the chart.
 */
@Composable
fun SpaceWeatherWeekChart(days: List<SpaceWeatherDay>, modifier: Modifier = Modifier) {
    val scale = GaugeScales.KP
    val gridColor = MaterialTheme.colorScheme.outline
    val selectionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = SELECTION_ALPHA)
    var selected by remember(days) { mutableIntStateOf(0) }
    val columnCount = days.size.coerceAtLeast(1)
    Column(modifier = modifier) {
        days.getOrNull(selected)?.let { DayDetails(it) }
        Column(
            modifier = Modifier.fillMaxWidth().pointerInput(columnCount) {
                detectTapGestures { tap -> selected = (tap.x / size.width * columnCount).toInt().coerceIn(0, columnCount - 1) }
            },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
                val brush = Brush.verticalGradient(*scale.gradientStops(COLOR_TRANSITION_KP), startY = size.height, endY = 0f)
                val slotWidth = size.width / columnCount
                val barWidth = slotWidth * BAR_WIDTH_SHARE
                drawRoundRect(
                    color = selectionColor,
                    topLeft = Offset(selected * slotWidth, 0f),
                    size = Size(slotWidth, size.height),
                    cornerRadius = CornerRadius(BAR_CORNER.toPx()),
                )
                days.forEachIndexed { index, day ->
                    val top = size.height * (1 - scale.fraction(day.maxKp))
                    drawRoundRect(
                        brush = brush,
                        topLeft = Offset(index * slotWidth + (slotWidth - barWidth) / 2, top),
                        size = Size(barWidth, size.height - top),
                        cornerRadius = CornerRadius(BAR_CORNER.toPx()),
                        alpha = if (day.forecast) FORECAST_ALPHA else 1f,
                    )
                }
                val stormY = size.height * (1 - scale.fraction(GeomagneticLevel.G1.minKp - KP_ROUNDING))
                drawLine(
                    color = gridColor.copy(alpha = GRID_ALPHA),
                    start = Offset(0f, stormY),
                    end = Offset(size.width, stormY),
                    strokeWidth = GRID_STROKE.toPx(),
                    pathEffect = PathEffect.dashPathEffect(GRID_DASH),
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                days.forEach { day -> DayLabel(day, Modifier.weight(1f)) }
        }
        }
    }
}

/** The selected day in words: level, Kp, solar activity and whether it is observed or forecast. */
@Composable
private fun DayDetails(day: SpaceWeatherDay) {
    val flux = day.radioFlux?.let { flux ->
        stringResource(R.string.flux_with_level, flux, day.solarActivity?.let { stringResource(it.label) } ?: MISSING)
    } ?: stringResource(R.string.flux_missing, MISSING)
    val origin = stringResource(if (day.forecast) R.string.day_forecast else R.string.day_observed)
    Text(text = stringResource(day.level.label), style = MaterialTheme.typography.titleSmall, color = GaugeScales.kpColor(day.level))
    Text(
        text = stringResource(R.string.day_details, DETAIL_DATE_FORMAT.inCurrentLocale().format(day.date), day.maxKp, flux, origin),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DayLabel(day: SpaceWeatherDay, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "%.1f".format(day.maxKp), style = MaterialTheme.typography.labelLarge, color = GaugeScales.kpColor(day.level))
        Text(text = WEEKDAY_FORMAT.inCurrentLocale().format(day.date), style = MaterialTheme.typography.labelSmall)
        Text(text = DATE_FORMAT.format(day.date), style = MaterialTheme.typography.labelSmall, color = muted)
        Text(text = day.radioFlux?.let { "☀$it" } ?: "☀-", style = MaterialTheme.typography.labelSmall, color = muted)
    }
}
