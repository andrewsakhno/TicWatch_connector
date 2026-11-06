package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import org.sand.ticwatch.phone.watchsettings.DaytimeHrvInterval
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
            label = stringResource(R.string.setting_night_bp),
            hint = stringResource(R.string.setting_night_bp_hint),
            checked = settings?.nightBloodPressure,
            onCheckedChange = { on -> change { it.copy(nightBloodPressure = on) } },
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
            hint = stringResource(R.string.setting_night_pulse_wave_hint),
            checked = settings?.nightPulseWave,
            onCheckedChange = { on -> change { it.copy(nightPulseWave = on) } },
        )
        SettingSwitch(
            label = stringResource(R.string.setting_night_monitor_intermittent),
            hint = stringResource(R.string.setting_night_monitor_intermittent_hint),
            checked = settings?.nightMonitorIntermittent,
            onCheckedChange = { on -> change { it.copy(nightMonitorIntermittent = on) } },
        )
        DaytimeHrvIntervalChoice(
            selected = settings?.daytimeHrvInterval,
            onSelect = { interval -> change { it.copy(daytimeHrvInterval = interval) } },
        )
    }
}

/** Each daytime RMSSD window lights the PPG for 3 minutes, so a shorter interval costs battery in proportion. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaytimeHrvIntervalChoice(selected: DaytimeHrvInterval?, onSelect: (DaytimeHrvInterval) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = stringResource(R.string.setting_daytime_hrv_interval))
        Text(text = stringResource(R.string.setting_daytime_hrv_interval_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow {
            DaytimeHrvInterval.entries.forEach { interval ->
                FilterChip(
                    selected = interval == selected,
                    onClick = { onSelect(interval) },
                    enabled = selected != null,
                    label = { Text(stringResource(R.string.setting_daytime_hrv_interval_minutes, interval.minutes)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
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
private fun SettingSwitch(label: String, hint: String? = null, checked: Boolean?, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(text = label)
            hint?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked == true, enabled = checked != null, onCheckedChange = onCheckedChange)
    }
}
