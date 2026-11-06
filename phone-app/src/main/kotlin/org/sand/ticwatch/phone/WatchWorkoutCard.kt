package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.sand.ticwatch.phone.datalayer.WatchWorkoutCommands
import org.sand.ticwatch.phone.route.WatchWorkoutActivityStore
import org.sand.ticwatch.phone.text.asString
import org.sand.ticwatch.phone.ui.rememberStore

/** Starts and ends the watch's walk workout from the phone. */
@Composable
internal fun WatchWorkoutCard(commands: WatchWorkoutCommands, activityStore: WatchWorkoutActivityStore) {
    val store = rememberStore(commands, activityStore) { scope, io -> WatchWorkoutStore(commands, activityStore, scope, io) }
    val state by store.state.collectAsState()
    val running = state.activity?.takeIf { it.isRunning(Instant.now()) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = stringResource(R.string.card_watch_workout), style = MaterialTheme.typography.titleMedium)
            if (running != null) {
                Text(
                    text = stringResource(R.string.workout_in_progress_since, SINCE_FORMAT.format(running.since)),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(text = stringResource(R.string.workout_auto_end_hint), style = MaterialTheme.typography.bodySmall)
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(enabled = !state.sending && running == null, onClick = store::start) { Text(stringResource(R.string.workout_start)) }
                OutlinedButton(enabled = !state.sending, onClick = store::end) { Text(stringResource(R.string.workout_end)) }
            }
            state.status?.let { Text(text = it.asString(), style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(text = it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private val SINCE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
