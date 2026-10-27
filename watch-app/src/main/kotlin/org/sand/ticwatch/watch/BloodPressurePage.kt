package org.sand.ticwatch.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.bloodpressure.BloodPressureEntry
import org.sand.ticwatch.watch.bloodpressure.BloodPressureHistory
import org.sand.ticwatch.watch.bloodpressure.BloodPressureMeasureResult
import org.sand.ticwatch.watch.dashboard.BloodPressureChart
import org.sand.ticwatch.watch.dashboard.BloodPressureChartColors
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the blood pressure page shows: the phone's saved readings (cuff and wrist estimates), the last measure
 * outcome, and [onMeasure] (null while the sensor service isn't bound, which disables the button). [autoMeasure] is
 * the automatic-measurement setting (null while it loads), switched by [onAutoMeasureChange].
 */
data class BloodPressurePageState(
    val history: StateFlow<BloodPressureHistory?>?,
    val lastResult: StateFlow<BloodPressureMeasureResult?>?,
    val onMeasure: (() -> Unit)?,
    val autoMeasure: Boolean?,
    val onAutoMeasureChange: (Boolean) -> Unit,
)

private const val MISSING = "--"
private val NO_HISTORY: StateFlow<BloodPressureHistory?> = MutableStateFlow(null)
private val NO_RESULT: StateFlow<BloodPressureMeasureResult?> = MutableStateFlow(null)

/** The phone flushes, estimates and answers within seconds; past this the button is offered again. */
private const val MEASURE_TIMEOUT_MS = 45_000L

private val SUCCESS_COLOR = Color(0xFF66BB6A)
private val TODAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

/** The phone sends the last day's readings; the chart spans the same day. */
private val CHART_WINDOW: Duration = Duration.ofHours(24)
private val CHART_HEIGHT = 72.dp
private val LEGEND_SPACING = 6.dp

/**
 * Latest reading in large digits, the last day's chart (tap a point for its details), a "Measure" button (wrist
 * estimate, saved by the phone) and the automatic-measurement switch; the column scrolls vertically.
 */
@Composable
internal fun BloodPressurePage(state: BloodPressurePageState) {
    val history by (state.history ?: NO_HISTORY).collectAsState()
    val result by (state.lastResult ?: NO_RESULT).collectAsState()
    var measuring by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BloodPressureEntry?>(null) }

    // Any new answer (or a local delivery failure) ends the wait; the timeout covers a lost answer.
    LaunchedEffect(result?.at) { measuring = false }
    LaunchedEffect(measuring) {
        if (measuring) {
            delay(MEASURE_TIMEOUT_MS)
            measuring = false
        }
    }

    val readings = history?.readings.orEmpty()
    val latest = readings.firstOrNull()
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("Blood pressure", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            Text(latest?.let { "${it.systolic}/${it.diastolic}" } ?: MISSING, style = MaterialTheme.typography.display2)
        }
        item {
            Text(
                latest?.let { entry -> listOfNotNull("mmHg", entry.pulse?.let { "pulse $it" }).joinToString(" · ") } ?: "mmHg",
                style = MaterialTheme.typography.caption2,
            )
        }
        latest?.let { entry ->
            item {
                Text(
                    "${entry.category}\n${entry.source} · ${formatTime(entry.measuredAt)}",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            val now = Instant.now()
            BloodPressureChart(
                readings = readings.filter { it.measuredAt.isAfter(now.minus(CHART_WINDOW)) }.asReversed(),
                start = now.minus(CHART_WINDOW),
                end = now,
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier.fillMaxWidth().height(CHART_HEIGHT),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(LEGEND_SPACING)) {
                Text("SYS", style = MaterialTheme.typography.caption3, color = BloodPressureChartColors.SYSTOLIC)
                Text("DIA", style = MaterialTheme.typography.caption3, color = BloodPressureChartColors.DIASTOLIC)
                Text("CUFF", style = MaterialTheme.typography.caption3, color = BloodPressureChartColors.CUFF)
                Text("last 24 h", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onSurfaceVariant)
            }
        }
        item { SelectedReading(selected) }
        item {
            Chip(
                onClick = {
                    measuring = true
                    state.onMeasure?.invoke()
                },
                enabled = state.onMeasure != null && !measuring,
                label = { Text(if (measuring) "Measuring..." else "Measure") },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        result?.let { last ->
            item {
                Text(
                    "${last.status} · ${formatTime(last.at)}",
                    style = MaterialTheme.typography.caption3,
                    color = if (last.success) SUCCESS_COLOR else MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            ToggleChip(
                checked = state.autoMeasure == true,
                onCheckedChange = state.onAutoMeasureChange,
                enabled = state.autoMeasure != null,
                label = { Text("Auto at rest") },
                secondaryLabel = { Text("Every 5 min") },
                toggleControl = { Switch(checked = state.autoMeasure == true) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SelectedReading(entry: BloodPressureEntry?) {
    Text(
        entry?.let { reading ->
            listOfNotNull(
                "${formatTime(reading.measuredAt)}  ${reading.systolic}/${reading.diastolic}",
                reading.pulse?.let { "pulse $it" },
                reading.source,
            ).joinToString(" · ") + "\n${reading.category}"
        } ?: "Tap the chart for a reading",
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatTime(instant: Instant): String {
    val local = instant.atZone(ZoneId.systemDefault())
    return (if (local.toLocalDate() == LocalDate.now()) TODAY_FORMAT else DATE_FORMAT).format(local)
}
