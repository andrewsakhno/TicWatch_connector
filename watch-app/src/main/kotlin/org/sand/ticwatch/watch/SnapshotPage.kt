package org.sand.ticwatch.watch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.snapshot.SnapshotState
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The snapshot page's [state] (null while the sensor service isn't bound) and [onCapture] (null disables the button). */
data class SnapshotPageState(
    val state: StateFlow<SnapshotState>?,
    val onCapture: (() -> Unit)?,
)

private val NO_SNAPSHOT: StateFlow<SnapshotState> = MutableStateFlow(SnapshotState.Idle)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Unit per snapshot metric, in display order. */
private val SNAPSHOT_UNITS: Map<SensorType, String> = linkedMapOf(
    SensorType.HEART_RATE to "%.0f bpm",
    SensorType.SPO2 to "%.0f %%",
    SensorType.HRV_RMSSD to "%.0f ms",
    SensorType.RESPIRATION_RATE to "%.1f /min",
    SensorType.SKIN_TEMPERATURE to "%.1f °C",
    SensorType.AMBIENT_TEMPERATURE to "%.1f °C ambient",
    SensorType.BAROMETRIC_PRESSURE to "%.0f hPa",
    SensorType.AMBIENT_LIGHT to "%.0f lx",
)

/** "Take snapshot": every metric at once after a stillness check (~3 min), then the values it got. */
@Composable
internal fun SnapshotPage(page: SnapshotPageState) {
    val state by (page.state ?: NO_SNAPSHOT).collectAsState()
    val running = state == SnapshotState.Running
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("Snapshot", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            Chip(
                onClick = { page.onCapture?.invoke() },
                enabled = page.onCapture != null && !running,
                label = { Text(if (running) "Measuring..." else "Take snapshot") },
                secondaryLabel = { Text(if (running) "Keep the wrist still" else "All metrics, ~3 min") },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        (state as? SnapshotState.Done)?.let { done ->
            val result = done.result
            item {
                Text(
                    "${result.status.message} · ${TIME_FORMAT.format(result.finishedAt.atZone(ZoneId.systemDefault()))}" +
                        if (done.delivered) "" else "\nPhone unreachable, synced later",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                )
            }
            SNAPSHOT_UNITS.forEach { (type, pattern) ->
                result.samples.firstOrNull { it.type == type }?.let { sample ->
                    item { Text(pattern.format(sample.value), style = MaterialTheme.typography.body1) }
                }
            }
        }
    }
}
