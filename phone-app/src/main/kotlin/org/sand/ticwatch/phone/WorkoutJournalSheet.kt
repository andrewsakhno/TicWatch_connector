package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.phone.dashboard.HeartRateZoneChart
import org.sand.ticwatch.phone.dashboard.ZoneChartSize
import org.sand.ticwatch.phone.health.HealthServicesExerciseTypes
import org.sand.ticwatch.phone.insights.WorkoutEntry
import org.sand.ticwatch.phone.insights.WorkoutJournal
import org.sand.ticwatch.phone.insights.WorkoutPeriod
import org.sand.ticwatch.phone.insights.WorkoutPeriodZones
import org.sand.ticwatch.phone.insights.ZoneMinutesBucket
import org.sand.ticwatch.phone.ui.ConnectedChoiceRow
import org.sand.ticwatch.phone.ui.ReloadingStore
import org.sand.ticwatch.phone.ui.rememberStore

private const val METERS_PER_KM = 1000.0
private val SHEET_PADDING = 16.dp
private val ITEM_SPACING = 12.dp
private val SECTION_GAP = 8.dp

private val PERIOD_LABELS = mapOf(
    WorkoutPeriod.WEEK to R.string.workout_period_week,
    WorkoutPeriod.MONTH to R.string.workout_period_month,
    WorkoutPeriod.QUARTER to R.string.workout_period_quarter,
)

/**
 * Opened from the "Training load" card. With no period chosen: the week's workouts by day, each with its type, time,
 * distance and zones; a chosen period replaces them with its zone charts. Tapping the chosen period again clears it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkoutJournalSheet(
    journal: WorkoutJournal,
    periodZones: WorkoutPeriodZones,
    changes: Flow<*>,
    onDismiss: () -> Unit,
) {
    var period by rememberSaveable { mutableStateOf<WorkoutPeriod?>(null) }
    val store = rememberStore(journal) { scope, io -> ReloadingStore(scope, io, changes) { journal.lastWeek() } }
    val workouts by store.state.collectAsState()
    val days = remember(workouts) { workouts.orEmpty().groupBy { it.day } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SHEET_PADDING),
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
        ) {
            item { Text(text = stringResource(R.string.workout_journal_title), style = MaterialTheme.typography.titleLarge) }
            item { PeriodChooser(period, onSelect = { period = if (it == period) null else it }) }
            val chosen = period
            if (chosen != null) {
                item(key = chosen.name) { PeriodCharts(periodZones, chosen, changes) }
                return@LazyColumn
            }
            days.forEach { (day, entries) ->
                item(key = day.toEpochDay()) { DayHeader(day) }
                items(entries, key = { it.start.toEpochMilli() }) { WorkoutCard(it) }
            }
            when {
                workouts == null -> item { LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                days.isEmpty() -> item { Text(text = stringResource(R.string.no_data), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutEntry) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(SHEET_PADDING)) {
            Text(text = workoutTitle(workout), style = MaterialTheme.typography.titleMedium)
            Detail(
                stringResource(R.string.workout_time),
                "${formatTime(workout.start)} - ${formatTime(workout.end)} (${formatDuration(Duration.between(workout.start, workout.end))})",
            )
            Detail(stringResource(R.string.workout_distance), workout.distanceMeters?.let { stringResource(R.string.unit_km, it / METERS_PER_KM) })
            HeartRateZoneChart(workout.zoneMinutes, ZoneChartSize.COMPACT, Modifier.fillMaxWidth().padding(top = SECTION_GAP))
        }
    }
}

@Composable
private fun workoutTitle(workout: WorkoutEntry): String {
    val type = stringResource(HealthServicesExerciseTypes.label(workout.exerciseTypeId))
    return if (workout.automatic) stringResource(R.string.workout_automatic, type.lowercase()) else type
}

@Composable
private fun PeriodChooser(selected: WorkoutPeriod?, onSelect: (WorkoutPeriod) -> Unit) {
    ConnectedChoiceRow(
        options = WorkoutPeriod.entries,
        selected = selected,
        onSelect = onSelect,
        label = { stringResource(PERIOD_LABELS.getValue(it)) },
    )
}

/** The chosen period's zone charts, newest first: one per day for a week, one for the whole span otherwise. */
@Composable
private fun PeriodCharts(periodZones: WorkoutPeriodZones, period: WorkoutPeriod, changes: Flow<*>) {
    val store = rememberStore(periodZones, period) { scope, io -> ReloadingStore(scope, io, changes) { periodZones.buckets(period) } }
    val buckets by store.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(ITEM_SPACING)) {
        buckets?.forEach { ZoneBucketCard(it) } ?: LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ZoneBucketCard(bucket: ZoneMinutesBucket) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(SHEET_PADDING)) {
            if (bucket.from == bucket.to) DayHeader(bucket.from) else SpanHeader(bucket)
            if (bucket.minutes.isEmpty()) {
                Text(text = stringResource(R.string.no_data), style = MaterialTheme.typography.bodyMedium)
            } else {
                HeartRateZoneChart(bucket.minutes, ZoneChartSize.REGULAR, Modifier.fillMaxWidth().padding(top = SECTION_GAP))
            }
        }
    }
}

@Composable
private fun SpanHeader(bucket: ZoneMinutesBucket) {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    Text(
        text = stringResource(R.string.workout_period_span, format.format(bucket.from), format.format(bucket.to)),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
