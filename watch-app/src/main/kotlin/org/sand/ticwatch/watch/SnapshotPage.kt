package org.sand.ticwatch.watch

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.R
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.snapshot.SnapshotState

/** The snapshot page's [state] (null while the sensor service isn't bound) and [onCapture] (null disables the button). */
data class SnapshotPageState(
    val state: StateFlow<SnapshotState>?,
    val onCapture: (() -> Unit)?,
)

private val NO_SNAPSHOT: StateFlow<SnapshotState> = MutableStateFlow(SnapshotState.Idle)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Unit per snapshot metric, in display order. */
private val SNAPSHOT_UNITS: Map<SensorType, Int> = linkedMapOf(
    SensorType.HEART_RATE to R.string.pattern_bpm,
    SensorType.SPO2 to R.string.snapshot_unit_spo2,
    SensorType.HRV_RMSSD to R.string.pattern_ms,
    SensorType.RESPIRATION_RATE to R.string.pattern_per_min,
    SensorType.SKIN_TEMPERATURE to R.string.pattern_celsius,
    SensorType.AMBIENT_TEMPERATURE to R.string.snapshot_unit_ambient,
    SensorType.BAROMETRIC_PRESSURE to R.string.snapshot_unit_pressure,
    SensorType.AMBIENT_LIGHT to R.string.snapshot_unit_light,
)

/** "Take snapshot": every metric at once after a stillness check (~3 min), then the values it got. */
@Composable
internal fun SnapshotPage(page: SnapshotPageState) {
    val state by (page.state ?: NO_SNAPSHOT).collectAsState()
    val running = state == SnapshotState.Running
    ScalingLazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text(stringResource(R.string.snapshot), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(
                onClick = { page.onCapture?.invoke() },
                enabled = page.onCapture != null && !running,
                label = { Text(stringResource(if (running) R.string.measuring else R.string.take_snapshot)) },
                secondaryLabel = { Text(stringResource(if (running) R.string.keep_wrist_still else R.string.all_metrics)) },
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        (state as? SnapshotState.Done)?.let { done ->
            val result = done.result
            item {
                Text(
                    "${stringResource(result.status.message)} · ${TIME_FORMAT.format(result.finishedAt.atZone(ZoneId.systemDefault()))}" +
                        if (done.delivered) "" else stringResource(R.string.snapshot_not_delivered),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            SNAPSHOT_UNITS.forEach { (type, pattern) ->
                result.samples.firstOrNull { it.type == type }?.let { sample ->
                    item { Text(stringResource(pattern).format(sample.value), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}
