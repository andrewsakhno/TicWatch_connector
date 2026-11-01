package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.ecg.EcgRetention
import org.sand.ticwatch.phone.history.HistoryRetention
import org.sand.ticwatch.phone.history.SleepSessionRetention
import org.sand.ticwatch.phone.ui.rememberStore

/**
 * Transfer-history settings: raw-sample, sleep-session and cardiogram retention (each purges immediately
 * on change, then daily), current on-disk size and a wipe button.
 */
@Composable
fun HistorySettingsSection(appContainer: AppContainer) {
    val store = rememberStore(appContainer) { scope, io ->
        with(appContainer) {
            HistorySettingsStore(
                historyRetentionStore, sleepSessionRetentionStore, ecgRetentionStore, ecgRecordingPurger,
                sensorHistoryMaintenance, sensorHistoryPurger,
                historyChanges.changes, scope, io,
            )
        }
    }
    val state by store.state.collectAsState()
    var isConfirmingClear by remember { mutableStateOf(false) }
    val isBusy = state.busy
    val sizeBytes = state.sizeBytes

    Column {
        Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.history_keep))
        SettingDropdown(state.retention, HistoryRetention.entries, { daysLabel(it.days.toInt()) }, enabled = !isBusy, onSelect = store::setRetention)
        Text(text = stringResource(R.string.history_keep_sleep))
        SettingDropdown(
            state.sleepRetention, SleepSessionRetention.entries, { sleepRetentionLabel(it) }, enabled = !isBusy,
            onSelect = store::setSleepRetention,
        )
        Text(text = stringResource(R.string.history_keep_ecg))
        SettingDropdown(state.ecgRetention, EcgRetention.entries, { daysLabel(it.days.toInt()) }, enabled = !isBusy, onSelect = store::setEcgRetention)
        Text(text = stringResource(R.string.history_size, sizeBytes?.let(::formatBytes) ?: LOADING))

        Spacer(modifier = Modifier.height(8.dp))
        Button(enabled = !isBusy, onClick = { isConfirmingClear = true }) {
            Text(text = stringResource(R.string.history_clear))
        }
    }

    if (isConfirmingClear) {
        AlertDialog(
            onDismissRequest = { isConfirmingClear = false },
            title = { Text(text = stringResource(R.string.history_clear_title)) },
            text = { Text(text = stringResource(R.string.history_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    isConfirmingClear = false
                    store.clear()
                }) { Text(text = stringResource(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingClear = false }) { Text(text = stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Button showing [selected] that opens a menu of [options]; disabled until [selected] has loaded. */
@Composable
internal fun <T> SettingDropdown(
    selected: T?,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = selected != null && enabled, onClick = { isMenuExpanded = true }) {
            Text(text = selected?.let { labelOf(it) } ?: LOADING)
        }
        DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = labelOf(option)) },
                    onClick = {
                        isMenuExpanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun daysLabel(days: Int): String = pluralStringResource(R.plurals.days_count, days, days)

@Composable
private fun sleepRetentionLabel(retention: SleepSessionRetention): String =
    retention.months?.toInt()?.let { pluralStringResource(R.plurals.months_count, it, it) } ?: stringResource(R.string.forever)

private const val LOADING = "..."
