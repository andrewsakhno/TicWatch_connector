package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlin.math.roundToInt
import org.sand.ticwatch.phone.deviation.DeviationEpisode
import org.sand.ticwatch.phone.deviation.DeviationEpisodesStore
import org.sand.ticwatch.phone.deviation.DeviationIntensity
import org.sand.ticwatch.phone.deviation.DeviationPeriodFormat
import org.sand.ticwatch.phone.deviation.DeviationReason
import org.sand.ticwatch.phone.ui.ConnectedChoiceRow

/** Human in the loop: every deviation episode asks why it happened, and only an answer lets it count for calibration. */
@Composable
fun DeviationEpisodesScreen(store: DeviationEpisodesStore, zone: ZoneId) {
    val state by store.state.collectAsState()
    val format = remember(zone) { DeviationPeriodFormat(zone) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(text = stringResource(R.string.deviation_intro), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.waiting.isEmpty()) {
            item { Text(text = stringResource(R.string.deviation_empty)) }
        }
        items(state.waiting, key = { it.id }) { episode ->
            DeviationEpisodeCard(episode, state.reasons, format, store::answer, store::discard)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviationEpisodeCard(
    episode: DeviationEpisode,
    reasons: List<DeviationReason>,
    format: DeviationPeriodFormat,
    onAnswer: (Long, Set<DeviationReason>, DeviationIntensity?) -> Unit,
    onDiscard: (Long) -> Unit,
) {
    var selected by remember(episode.id, episode.reasons) { mutableStateOf(episode.reasons) }
    var intensity by remember(episode.id, episode.intensity) { mutableStateOf(episode.intensity) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Box {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(episode.direction.label, episode.peakBpm.roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                    // Keeps the title clear of the close button in the corner.
                    modifier = Modifier.padding(end = 40.dp),
                )
                Text(text = "${stringResource(episode.state.label)}, ${format.period(episode)}", style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(episode.calibrationUse.label), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    reasons.forEach { reason ->
                        val chosen = reason in selected
                        FilterChip(
                            selected = chosen,
                            onClick = { selected = if (chosen) selected - reason else selected + reason },
                            label = { Text(text = stringResource(reason.label)) },
                        )
                    }
                }
                Text(text = stringResource(R.string.deviation_intensity_title), style = MaterialTheme.typography.bodySmall)
                ConnectedChoiceRow(
                    options = DeviationIntensity.entries,
                    selected = intensity,
                    onSelect = { level -> intensity = level.takeUnless { it == intensity } },
                    label = { stringResource(it.label) },
                )
                Button(onClick = { onAnswer(episode.id, selected, intensity) }, enabled = selected.isNotEmpty()) {
                    Text(text = stringResource(R.string.deviation_save))
                }
            }
            IconButton(onClick = { onDiscard(episode.id) }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.deviation_discard))
            }
        }
    }
}
