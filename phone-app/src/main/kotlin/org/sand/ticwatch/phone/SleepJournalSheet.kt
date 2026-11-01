package org.sand.ticwatch.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import org.sand.ticwatch.phone.dashboard.GaugeScales
import org.sand.ticwatch.phone.dashboard.SleepHypnogram
import org.sand.ticwatch.phone.dashboard.sleepStageColor
import org.sand.ticwatch.phone.health.SleepStageType
import org.sand.ticwatch.phone.sleep.SleepJournal
import org.sand.ticwatch.phone.sleep.SleepJournalEntry
import org.sand.ticwatch.phone.sleep.SleepJournalStore
import org.sand.ticwatch.phone.ui.rememberStore

private const val PRELOAD_ITEMS = 3
private val SHEET_PADDING = 16.dp
private val ITEM_SPACING = 12.dp
private val SECTION_GAP = 8.dp
private val LEGEND_DOT = 10.dp
private val STAGE_LABELS = linkedMapOf(
    SleepStageType.AWAKE to R.string.stage_awake,
    SleepStageType.REM to R.string.stage_rem,
    SleepStageType.LIGHT to R.string.stage_light,
    SleepStageType.SLEEPING to R.string.stage_sleeping,
    SleepStageType.DEEP to R.string.stage_deep,
)

/** Opened from the "Sleep score" card: every stored sleep, grouped by the day it ended on, with its stages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SleepJournalSheet(journal: SleepJournal, changes: Flow<*>, onDismiss: () -> Unit) {
    val store = rememberStore(journal) { scope, io -> SleepJournalStore(scope, io, changes, journal) }
    val state by store.state.collectAsState()
    val days = remember(state.entries) { state.entries.groupBy { it.wakeDay } }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.entries.size) {
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - PRELOAD_ITEMS
        }.filter { it }.collect { store.loadOlder() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            item { Text(text = stringResource(R.string.sleep_journal_title), style = MaterialTheme.typography.titleLarge) }
            days.forEach { (day, entries) ->
                item(key = day.toEpochDay()) { DayHeader(day) }
                items(entries, key = { it.session.start.toEpochMilli() }) { SleepEntryCard(it) }
            }
            when {
                state.loading -> item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                state.entries.isEmpty() -> item { Text(text = stringResource(R.string.no_data), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
internal fun DayHeader(day: LocalDate) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }
    Text(text = format.format(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SleepEntryCard(entry: SleepJournalEntry) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(SHEET_PADDING)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = formatDuration(entry.duration), style = MaterialTheme.typography.titleMedium)
                entry.score?.let { score ->
                    Text(
                        text = stringResource(R.string.detail_line, stringResource(R.string.card_sleep_score), score.toString()),
                        style = MaterialTheme.typography.labelLarge,
                        color = GaugeScales.SLEEP_SCORE.bandOf(score.toDouble()).color,
                    )
                }
            }
            if (entry.stages.isEmpty()) {
                Text(text = timeRange(entry), style = MaterialTheme.typography.bodySmall)
                Text(text = stringResource(R.string.sleep_no_stages), style = MaterialTheme.typography.bodyMedium)
            } else {
                Box(modifier = Modifier.padding(vertical = SECTION_GAP)) {
                    SleepHypnogram(entry.stages, entry.session.start, entry.session.end)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = formatTime(entry.session.start), style = MaterialTheme.typography.labelSmall)
                    Text(text = formatTime(entry.session.end), style = MaterialTheme.typography.labelSmall)
                }
                StageTotals(entry.stageTotals, Modifier.padding(top = SECTION_GAP))
            }
            entry.insights?.let { SleepInsightsSection(it, Modifier.padding(top = SECTION_GAP)) }
        }
    }
}

private fun timeRange(entry: SleepJournalEntry): String = "${formatTime(entry.session.start)} - ${formatTime(entry.session.end)}"

@Composable
private fun StageTotals(totals: Map<SleepStageType, Duration>, modifier: Modifier = Modifier) {
    val staged = totals.values.sumOf { it.toMillis() }.toDouble()
    Column(modifier = modifier) {
        STAGE_LABELS.forEach { (type, label) ->
            val total = totals[type] ?: return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.padding(end = SECTION_GAP).size(LEGEND_DOT).background(sleepStageColor(type), CircleShape))
                val value = "${formatDuration(total)} · ${formatShare(total.toMillis() / staged)}"
                Text(text = stringResource(R.string.detail_line, stringResource(label), value), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
