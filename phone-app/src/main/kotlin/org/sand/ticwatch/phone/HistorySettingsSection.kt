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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.history.HistoryRetention
import org.sand.ticwatch.phone.history.SleepSessionRetention

/**
 * Transfer-history settings: raw-sample and sleep-session retention (each purges immediately
 * on change, then daily), current on-disk size and a wipe button.
 */
@Composable
fun HistorySettingsSection(appContainer: AppContainer) {
    val coroutineScope = rememberCoroutineScope()

    var retention by remember { mutableStateOf<HistoryRetention?>(null) }
    var sleepRetention by remember { mutableStateOf<SleepSessionRetention?>(null) }
    var sizeBytes by remember { mutableStateOf<Long?>(null) }
    var isConfirmingClear by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    suspend fun refreshSize() {
        sizeBytes = appContainer.sensorHistoryMaintenance.storageSizeBytes()
    }

    fun runBusy(action: suspend () -> Unit) {
        isBusy = true
        coroutineScope.launch {
            runCatching { action() }
            runCatching { refreshSize() }
            isBusy = false
        }
    }

    LaunchedEffect(Unit) {
        retention = appContainer.historyRetentionStore.get()
        sleepRetention = appContainer.sleepSessionRetentionStore.get()
        refreshSize()
    }

    Column {
        Text(text = "Transfer history", style = MaterialTheme.typography.titleMedium)
        Text(text = "Keep history for")
        SettingDropdown(retention, HistoryRetention.entries, ::retentionLabel, enabled = !isBusy) { option ->
            retention = option
            runBusy {
                appContainer.historyRetentionStore.set(option)
                appContainer.sensorHistoryPurger.purge()
            }
        }
        Text(text = "Keep sleep sessions for")
        SettingDropdown(sleepRetention, SleepSessionRetention.entries, ::sleepRetentionLabel, enabled = !isBusy) { option ->
            sleepRetention = option
            runBusy {
                appContainer.sleepSessionRetentionStore.set(option)
                appContainer.sensorHistoryPurger.purge()
            }
        }
        Text(text = "History size: ${sizeBytes?.let(::formatBytes) ?: "..."}")

        Spacer(modifier = Modifier.height(8.dp))
        Button(enabled = !isBusy, onClick = { isConfirmingClear = true }) {
            Text(text = "Clear history")
        }
    }

    if (isConfirmingClear) {
        AlertDialog(
            onDismissRequest = { isConfirmingClear = false },
            title = { Text(text = "Clear history?") },
            text = { Text(text = "All stored transfer history will be deleted. Data already in Google Health is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    isConfirmingClear = false
                    runBusy { appContainer.sensorHistoryMaintenance.clear() }
                }) { Text(text = "Clear") }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingClear = false }) { Text(text = "Cancel") }
            },
        )
    }
}

/** Button showing [selected] that opens a menu of [options]; disabled until [selected] has loaded. */
@Composable
private fun <T> SettingDropdown(
    selected: T?,
    options: List<T>,
    labelOf: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = selected != null && enabled, onClick = { isMenuExpanded = true }) {
            Text(text = selected?.let(labelOf) ?: "...")
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

private fun retentionLabel(retention: HistoryRetention): String = "${retention.days} days"

private fun sleepRetentionLabel(retention: SleepSessionRetention): String =
    retention.months?.let { "$it months" } ?: "Forever"
