package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.snapshot.SnapshotResultLog
import org.sand.ticwatch.phone.snapshot.WatchSnapshotRequester
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

private val SNAPSHOT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

/** Stillness check + one ~3 min window + delivery; past this the button is offered again. */
private val SNAPSHOT_TIMEOUT = 5.minutes

/** Display pattern per snapshot metric, in display order. */
private val SNAPSHOT_PATTERNS: Map<SensorType, String> = linkedMapOf(
    SensorType.HEART_RATE to "Heart rate %.0f bpm",
    SensorType.SPO2 to "SpO2 %.0f %%",
    SensorType.HRV_RMSSD to "HRV %.0f ms",
    SensorType.RESPIRATION_RATE to "Breathing %.1f /min",
    SensorType.SKIN_TEMPERATURE to "Skin %.1f °C",
    SensorType.AMBIENT_TEMPERATURE to "Ambient %.1f °C",
    SensorType.BAROMETRIC_PRESSURE to "Pressure %.0f hPa",
    SensorType.AMBIENT_LIGHT to "Light %.0f lx",
)

/** "Take snapshot from watch": asks the watch for all metrics at once and shows the latest snapshot. */
@Composable
internal fun WatchSnapshotCard(requester: WatchSnapshotRequester, results: SnapshotResultLog) {
    val latest by results.latest.collectAsState()
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Watch snapshot", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !waiting,
                onClick = {
                    waiting = true
                    error = null
                    scope.launch {
                        requester.requestAndAwait(SNAPSHOT_TIMEOUT)
                            .onSuccess { if (it == null) error = "No answer from the watch" }
                            .onFailure { error = "Watch unreachable: ${it.message}" }
                        waiting = false
                    }
                },
                modifier = Modifier.padding(vertical = 8.dp),
            ) { Text(if (waiting) "Measuring on the watch (~3 min)..." else "Take snapshot from watch") }
            error?.let { Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            latest?.let { result ->
                Text(
                    text = "${result.message} · ${SNAPSHOT_TIME_FORMAT.format(result.finishedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SNAPSHOT_PATTERNS.forEach { (type, pattern) ->
                    result.samples.firstOrNull { it.type == type }?.let { sample ->
                        Text(text = pattern.format(sample.value), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
