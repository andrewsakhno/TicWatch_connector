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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.dashboard.TrendChart
import org.sand.ticwatch.phone.dashboard.TrendSeries
import org.sand.ticwatch.phone.insights.FATIGUE_INDEX_SCALE
import org.sand.ticwatch.phone.insights.FatigueDayChart
import org.sand.ticwatch.phone.insights.FatigueHistory
import org.sand.ticwatch.phone.insights.FatigueHistorySpan
import org.sand.ticwatch.phone.ui.ConnectedChoiceRow
import org.sand.ticwatch.phone.ui.ReloadingStore
import org.sand.ticwatch.phone.ui.rememberStore

private val SHEET_PADDING = 16.dp
private val ITEM_SPACING = 12.dp
private val CHART_TITLE_SPACING = 4.dp
private val CHART_HEIGHT = 120.dp
private const val CHART_TITLE_SKELETON = "EEEEdMMMM"
private const val LEGEND_DATE_SKELETON = "dMMM"

private const val DATE_RANGE_SEPARATOR = " – "

/** The line colour of each day on a chart, newest first: as many as the longest [FatigueHistorySpan] overlays. */
private val DAY_COLORS = listOf(Color(0xFF42A5F5), Color(0xFFFFA726), Color(0xFF66BB6A), Color(0xFFAB47BC))

/** Opened from the "Fatigue index" card: seven charts of the index by hour, each overlaying the span's consecutive days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FatigueHistorySheet(history: FatigueHistory, changes: Flow<*>, onDismiss: () -> Unit) {
    val store = rememberStore(history) { scope, io -> ReloadingStore(scope, io, changes) { history.load() } }
    val hours by store.state.collectAsState()
    var span by remember { mutableStateOf(FatigueHistorySpan.ONE_WEEK) }
    val loaded = hours
    val charts = remember(loaded, span) { loaded?.charts(span).orEmpty() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SHEET_PADDING),
            contentPadding = PaddingValues(bottom = SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            item { Text(text = stringResource(R.string.fatigue_history_title), style = MaterialTheme.typography.titleLarge) }
            item { SpanChooser(span) { span = it } }
            if (loaded == null) {
                item { LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            } else {
                items(charts, key = { it.lines.first().date.toEpochDay() }) { FatigueDayCard(it) }
            }
        }
    }
}

@Composable
private fun SpanChooser(selected: FatigueHistorySpan, onSelect: (FatigueHistorySpan) -> Unit) {
    Column {
        Text(text = stringResource(R.string.report_period_days), style = MaterialTheme.typography.bodySmall)
        ConnectedChoiceRow(options = FatigueHistorySpan.entries, selected = selected, onSelect = onSelect, label = { "${it.days}" })
    }
}

@Composable
private fun FatigueDayCard(chart: FatigueDayChart) {
    val locale = LocalConfiguration.current.locales[0]
    val titleFormat = remember(locale) { dateFormat(CHART_TITLE_SKELETON, locale) }
    val legendFormat = remember(locale) { dateFormat(LEGEND_DATE_SKELETON, locale) }
    val timeFormat = remember(locale) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault()) }
    val newest = chart.lines.first().date
    val oldest = chart.lines.last().date
    val title = if (newest == oldest) titleFormat.format(newest) else legendFormat.format(oldest) + DATE_RANGE_SEPARATOR + legendFormat.format(newest)
    Column(verticalArrangement = Arrangement.spacedBy(CHART_TITLE_SPACING)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        TrendChart(
            series = chart.lines.mapIndexed { day, line -> TrendSeries(legendFormat.format(line.date), line.trend, DAY_COLORS[day]) },
            formatTime = timeFormat::format,
            valueRange = FATIGUE_INDEX_SCALE,
            formatScrubValue = { "${it.roundToInt()}%" },
            modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
        )
    }
}

private fun dateFormat(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
