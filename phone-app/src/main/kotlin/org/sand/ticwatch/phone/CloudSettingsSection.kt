package org.sand.ticwatch.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flow
import org.sand.ticwatch.phone.cloud.CloudLink
import org.sand.ticwatch.phone.cloud.CloudSettings
import org.sand.ticwatch.phone.cloud.CloudSettingsStore
import org.sand.ticwatch.phone.cloud.CloudSyncScheduler
import org.sand.ticwatch.phone.cloud.CloudSyncStatus
import org.sand.ticwatch.phone.cloud.CloudSyncStatusStore
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.ui.UiStateStore
import org.sand.ticwatch.phone.ui.rememberStore
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** [settings] null until loaded. */
data class CloudSettingsUiState(val settings: CloudSettings? = null, val status: CloudSyncStatus = CloudSyncStatus())

class CloudSettingsUiStore(
    private val settingsStore: () -> CloudSettingsStore,
    statusStore: () -> CloudSyncStatusStore,
    private val scheduler: CloudSyncScheduler,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<CloudSettingsUiState>(CloudSettingsUiState(), scope, ioDispatcher) {
    init {
        launchIo({ settingsStore().get() }) { result -> result.onSuccess { loaded -> setState { it.copy(settings = loaded) } } }
        observe(flow { statusStore().status.collect { emit(it) } }) { status -> setState { it.copy(status = status) } }
    }

    fun setEnabled(enabled: Boolean) = save { it.copy(enabled = enabled) }

    fun setManualHost(host: String) = save { it.copy(manualHost = host.trim()) }

    fun syncNow() = scheduler.runNow()

    /** Stores the change, then runs a round so the status reflects it at once. */
    private fun save(change: (CloudSettings) -> CloudSettings) {
        val updated = state.value.settings?.let(change) ?: return
        setState { it.copy(settings = updated) }
        launchIo({ settingsStore().set(updated) }) { scheduler.runNow() }
    }
}

/** Cloud storage: on/off, where the server is (found by scan or typed), last sync, "Sync now". */
@Composable
fun CloudSettingsSection(appContainer: AppContainer) {
    val store = rememberStore(appContainer) { scope, io ->
        CloudSettingsUiStore(
            { appContainer.cloudSettingsStore }, { appContainer.cloudSyncStatusStore }, appContainer.cloudSyncScheduler, scope, io,
        )
    }
    val state by store.state.collectAsState()
    val settings = state.settings
    val status = state.status
    var hostInput by remember { mutableStateOf("") }
    LaunchedEffect(settings?.manualHost) { settings?.let { hostInput = it.manualHost } }

    Column {
        Text(text = stringResource(R.string.cloud_title), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = stringResource(R.string.cloud_enabled), modifier = Modifier.weight(1f))
            Switch(checked = settings?.enabled == true, enabled = settings != null, onCheckedChange = store::setEnabled)
        }
        Text(text = linkLabel(status))
        status.lastSyncAt?.let { Text(text = stringResource(R.string.cloud_last_sync, SYNC_TIME_FORMAT.format(it))) }
        Text(text = stringResource(R.string.cloud_pending, status.pending))
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = hostInput,
            onValueChange = { hostInput = it },
            label = { Text(text = stringResource(R.string.cloud_manual_host)) },
            placeholder = { Text(text = stringResource(R.string.cloud_manual_host_hint)) },
            singleLine = true,
            enabled = settings != null,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            OutlinedButton(enabled = settings != null && hostInput.trim() != settings.manualHost, onClick = { store.setManualHost(hostInput) }) {
                Text(text = stringResource(R.string.cloud_save_host))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(enabled = settings?.enabled == true && !status.syncing, onClick = store::syncNow) {
                Text(text = stringResource(R.string.cloud_sync_now))
            }
        }
    }
}

@Composable
private fun linkLabel(status: CloudSyncStatus): String = when {
    status.syncing -> stringResource(R.string.cloud_status_syncing)
    status.link == CloudLink.DISABLED -> stringResource(R.string.cloud_link_disabled)
    status.link == CloudLink.CONNECTED -> stringResource(R.string.cloud_link_connected, status.host.orEmpty())
    else -> stringResource(R.string.cloud_link_no_server)
}

private val SYNC_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(SensorHistoryModule.zone)
