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
import androidx.compose.ui.res.stringResource
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
        Text(text = stringResource(R.string.watch_features), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.watch_features_hint))
        SettingSwitch(
            label = stringResource(R.string.setting_auto_bp),
            checked = settings?.autoBloodPressure,
            onCheckedChange = { on -> change { it.copy(autoBloodPressure = on) } },
        )
        SettingSwitch(
            label = stringResource(R.string.setting_log_capture),
            checked = settings?.logCapture,
            onCheckedChange = { on -> change { it.copy(logCapture = on) } },
        )
        SettingSwitch(
            label = stringResource(R.string.setting_snore_analysis),
            checked = settings?.snoreAnalysis,
            onCheckedChange = { on -> change { it.copy(snoreAnalysis = on) } },
        )
        SnoreMicrophoneChoice(
            selected = settings?.snoreMicrophone,
            onSelect = { microphone -> change { it.copy(snoreMicrophone = microphone) } },
        )
        SettingSwitch(
            label = stringResource(R.string.setting_night_pulse_wave),
            checked = settings?.nightPulseWave,
            onCheckedChange = { on -> change { it.copy(nightPulseWave = on) } },
        )
        SettingSwitch(
            label = stringResource(R.string.setting_night_monitor_intermittent),
            checked = settings?.nightMonitorIntermittent,
            onCheckedChange = { on -> change { it.copy(nightMonitorIntermittent = on) } },
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
        Text(text = stringResource(R.string.setting_snore_microphone), modifier = Modifier.weight(1f))
        SnoreMicrophone.entries.forEach { microphone ->
            FilterChip(
                selected = microphone == selected,
                onClick = { onSelect(microphone) },
                enabled = selected != null,
                label = { Text(stringResource(microphoneLabel(microphone))) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private fun microphoneLabel(microphone: SnoreMicrophone): Int = when (microphone) {
    SnoreMicrophone.WATCH -> R.string.microphone_watch
    SnoreMicrophone.PHONE -> R.string.microphone_phone
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
