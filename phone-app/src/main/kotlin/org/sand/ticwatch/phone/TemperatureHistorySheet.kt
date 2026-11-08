package org.sand.ticwatch.phone

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.dashboard.TrendChart
import org.sand.ticwatch.phone.dashboard.TrendSeries
import org.sand.ticwatch.phone.insights.TemperatureHistory
import org.sand.ticwatch.phone.insights.TemperatureSeries
import org.sand.ticwatch.phone.insights.TemperatureSpan
import org.sand.ticwatch.phone.insights.TemperatureWindow
import org.sand.ticwatch.phone.ui.ReloadingStore
import org.sand.ticwatch.phone.ui.rememberStore

private val SHEET_PADDING = 16.dp
private val ITEM_SPACING = 16.dp
private val CHART_TITLE_SPACING = 4.dp
private val DETAIL_CHART_HEIGHT = 160.dp
private const val AXIS_VALUE_FORMAT = "%.1f"
private const val TIME_SKELETON = "dMMMjjmm"
private const val DAY_MARKER_SKELETON = "dMMM"

/** The line colour of each temperature, the same on the card and in the sheet. */
private val SERIES_COLORS = mapOf(
    TemperatureSeries.BODY to Color(0xFFEF5350),
    TemperatureSeries.WRIST to Color(0xFFFFA726),
    TemperatureSeries.OUTDOOR to Color(0xFF42A5F5),
)

/** Opened from the "Temperature" card: one chart per [TemperatureSpan], each with all three temperatures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemperatureHistorySheet(history: TemperatureHistory, changes: Flow<*>, onDismiss: () -> Unit) {
    val store = rememberStore(history) { scope, io ->
        ReloadingStore(scope, io, changes) { TemperatureSpan.entries.map { history.window(it) } }
    }
    val windows by store.state.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SHEET_PADDING),
            contentPadding = PaddingValues(bottom = SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            item { Text(text = stringResource(R.string.card_temperature), style = MaterialTheme.typography.titleLarge) }
            val loaded = windows
            if (loaded == null) {
                item { LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(loaded, key = { it.span }) { window ->
                    Column(verticalArrangement = Arrangement.spacedBy(CHART_TITLE_SPACING)) {
                        Text(text = stringResource(window.span.label), style = MaterialTheme.typography.titleSmall)
                        TemperatureChart(window, DETAIL_CHART_HEIGHT)
                    }
                }
            }
        }
    }
}

/** All temperatures of [window] on one °C axis, with a dashed line at each local midnight. */
@Composable
internal fun TemperatureChart(window: TemperatureWindow, height: Dp) {
    val locale = LocalConfiguration.current.locales[0]
    val timeFormat = remember(locale) { dateFormat(TIME_SKELETON, locale) }
    val dayFormat = remember(locale) { dateFormat(DAY_MARKER_SKELETON, locale) }
    val unit = stringResource(R.string.unit_celsius)
    TrendChart(
        series = TemperatureSeries.entries.mapNotNull { series ->
            window.series[series]?.let { TrendSeries(stringResource(series.label), it, SERIES_COLORS.getValue(series)) }
        },
        formatTime = timeFormat::format,
        modifier = Modifier.fillMaxWidth().height(height),
        formatScrubValue = { unit.format(it) },
        formatAxisValue = { AXIS_VALUE_FORMAT.format(it) },
        dayMarkers = window.dayStarts,
        formatDayMarker = dayFormat::format,
        showPoints = false,
    )
}

private fun dateFormat(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).withZone(ZoneId.systemDefault())
