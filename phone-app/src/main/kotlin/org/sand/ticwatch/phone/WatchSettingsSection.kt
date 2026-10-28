package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.watchsettings.SnoreMicrophone
import org.sand.ticwatch.phone.watchsettings.WatchSettings

/**
 * Battery-heavy watch features, off by default; synced with the watch's own switches (newest change wins).
 */
@Composable
fun WatchSettingsSection(appContainer: AppContainer) {
    val coroutineScope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<WatchSettings?>(null) }

    LaunchedEffect(Unit) {
        val store = withContext(Dispatchers.IO) { appContainer.watchSettingsStore }
        // A change made on the watch shows up here too.
        store.settings.collect { settings = it }
    }

    fun change(transform: (WatchSettings) -> WatchSettings) {
        coroutineScope.launch(Dispatchers.IO) { appContainer.watchSettingsSync.change(transform) }
    }

    Column {
        Text(text = "Watch features", style = MaterialTheme.typography.titleMedium)
        Text(text = "They drain the watch battery (snore analysis only on the watch microphone); applied on the watch at the next contact.")
        SettingSwitch(
            label = "Auto blood pressure at rest (every 5 min)",
            checked = settings?.autoBloodPressure,
            onCheckedChange = { on -> change { it.copy(autoBloodPressure = on) } },
        )
        SettingSwitch(
            label = "Watch log capture (debug)",
            checked = settings?.logCapture,
            onCheckedChange = { on -> change { it.copy(logCapture = on) } },
        )
        SettingSwitch(
            label = "Snore analysis while asleep",
            checked = settings?.snoreAnalysis,
            onCheckedChange = { on -> change { it.copy(snoreAnalysis = on) } },
        )
        SnoreMicrophoneChoice(
            selected = settings?.snoreMicrophone,
            onSelect = { microphone -> change { it.copy(snoreMicrophone = microphone) } },
        )
    }
}

/** Watch microphone costs the watch battery; the phone's must lie next to the bed. */
@Composable
private fun SnoreMicrophoneChoice(selected: SnoreMicrophone?, onSelect: (SnoreMicrophone) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Snore microphone", modifier = Modifier.weight(1f))
        SnoreMicrophone.entries.forEach { microphone ->
            FilterChip(
                selected = microphone == selected,
                onClick = { onSelect(microphone) },
                enabled = selected != null,
                label = { Text(microphone.label) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean?, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked == true, enabled = checked != null, onCheckedChange = onCheckedChange)
    }
}
