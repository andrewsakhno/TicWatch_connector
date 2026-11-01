package org.sand.ticwatch.phone

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.snapshot.SnapshotResultLog
import org.sand.ticwatch.phone.snapshot.WatchSnapshotRequester
import org.sand.ticwatch.phone.text.asString

private val SNAPSHOT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

/** Stillness check + one ~3 min window + delivery; past this the button is offered again. */
private val SNAPSHOT_TIMEOUT = 5.minutes

private val ELAPSED_TICK = 1.seconds
private const val SECONDS_PER_MINUTE = 60

/** A snapshot metric's name while it is polled and its display pattern once measured. */
private class SnapshotMetricLabels(@StringRes val name: Int, @StringRes val pattern: Int)

/** Per snapshot metric, in display order. */
private val SNAPSHOT_METRICS: Map<SensorType, SnapshotMetricLabels> = linkedMapOf(
    SensorType.HEART_RATE to SnapshotMetricLabels(R.string.snapshot_metric_heart_rate, R.string.snapshot_heart_rate),
    SensorType.SPO2 to SnapshotMetricLabels(R.string.snapshot_metric_spo2, R.string.snapshot_spo2),
    SensorType.HRV_RMSSD to SnapshotMetricLabels(R.string.snapshot_metric_hrv, R.string.snapshot_hrv),
    SensorType.RESPIRATION_RATE to SnapshotMetricLabels(R.string.snapshot_metric_breathing, R.string.snapshot_breathing),
    SensorType.SKIN_TEMPERATURE to SnapshotMetricLabels(R.string.snapshot_metric_skin, R.string.snapshot_skin),
    SensorType.AMBIENT_TEMPERATURE to SnapshotMetricLabels(R.string.snapshot_metric_ambient, R.string.snapshot_ambient),
    SensorType.BAROMETRIC_PRESSURE to SnapshotMetricLabels(R.string.snapshot_metric_pressure, R.string.snapshot_pressure),
    SensorType.AMBIENT_LIGHT to SnapshotMetricLabels(R.string.snapshot_metric_light, R.string.snapshot_light),
)

/** The names of those of [metrics] the card knows, in display order and comma-separated; null when there are none. */
@Composable
private fun metricNames(metrics: Set<SensorType>): String? =
    SNAPSHOT_METRICS.filterKeys { it in metrics }.values
        .map { stringResource(it.name) }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

/** How long the current stage has been running, as m:ss. */
private fun elapsedLabel(since: Instant, now: Instant): String {
    val seconds = Duration.between(since, now).seconds.coerceAtLeast(0)
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

/** "Take snapshot from watch": asks the watch for all metrics at once and shows the latest snapshot. */
@Composable
internal fun WatchSnapshotCard(requester: WatchSnapshotRequester, results: SnapshotResultLog) {
    val latest by results.latest.collectAsState()
    val waiting by results.isRunning.collectAsState()
    val error by results.error.collectAsState()
    val progress by results.progress.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(waiting) {
        while (waiting) {
            now = Instant.now()
            delay(ELAPSED_TICK)
        }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.card_watch_snapshot), style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !waiting,
                onClick = { results.requestSnapshot(requester, SNAPSHOT_TIMEOUT) },
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                if (waiting) {
                    Column {
                        Text(stringResource(R.string.snapshot_stage_elapsed, stringResource(progress.stage.label), elapsedLabel(progress.since, now)))
                        val metrics = progress.metrics
                        metricNames(metrics.answering)?.let {
                            Text(stringResource(R.string.snapshot_metrics_answering, it), style = MaterialTheme.typography.bodySmall)
                        }
                        metricNames(metrics.polled - metrics.answering)?.let {
                            Text(stringResource(R.string.snapshot_metrics_waiting, it), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text(stringResource(R.string.snapshot_take))
                }
            }
            error?.let { Text(text = it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            latest?.let { result ->
                Text(
                    text = "${result.message} · ${SNAPSHOT_TIME_FORMAT.format(result.finishedAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SNAPSHOT_METRICS.forEach { (type, labels) ->
                    result.samples.firstOrNull { it.type == type }?.let { sample ->
                        Text(text = stringResource(labels.pattern, sample.value), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
