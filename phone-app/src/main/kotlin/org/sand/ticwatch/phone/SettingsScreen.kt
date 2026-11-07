package org.sand.ticwatch.phone

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.ui.rememberStore

/**
 * Lets the user pick a watch-app APK from local storage and push it to a paired watch
 * via [AppContainer.watchAppManager]. Reuses the same install path the dashboard would
 * use for a first install: PackageInstaller treats a matching packageName as an update.
 */
@Composable
fun SettingsScreen(appContainer: AppContainer) {
    val context = LocalContext.current
    val store = rememberStore(appContainer) { scope, io ->
        WatchAppUpdateStore(appContainer.watchAppManager, { uri -> copyUriToCacheFile(context, uri) }, scope, io)
    }
    val state by store.state.collectAsState()
    val watches = state.watches
    val selectedWatchId = state.selectedWatchId
    val selectedApkUri = state.selectedApkUri

    val pickApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = store::selectApk,
    )

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        LanguageSettingsSection(appContainer)
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = stringResource(R.string.watch_app_apk), style = MaterialTheme.typography.titleMedium)
        Button(onClick = { pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive")) }) {
            Text(text = stringResource(R.string.select_apk))
        }
        Text(text = selectedApkUri?.lastPathSegment ?: stringResource(R.string.no_apk_selected))

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = stringResource(R.string.target_watch), style = MaterialTheme.typography.titleMedium)
        if (watches.isEmpty()) {
            Text(text = stringResource(R.string.no_paired_watches))
        } else {
            Column(modifier = Modifier.selectableGroup()) {
                watches.forEach { watch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = watch.id == selectedWatchId,
                                onClick = { store.selectWatch(watch.id) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = watch.id == selectedWatchId, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(if (watch.isNearby) R.string.watch_nearby else R.string.watch_reachable, watch.displayName))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            enabled = state.canUpdate,
            onClick = store::update,
        ) {
            Text(text = stringResource(R.string.update_app_on_watch))
        }

        if (state.updating) {
            Spacer(modifier = Modifier.height(8.dp))
            LoadingIndicator()
        }

        state.outcome?.let { outcome ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (outcome) {
                    WatchAppUpdateOutcome.Updated -> stringResource(R.string.watch_app_updated)
                    is WatchAppUpdateOutcome.Failed -> stringResource(R.string.watch_app_update_failed, outcome.reason.orEmpty())
                },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        WatchSettingsSection(appContainer)
        Spacer(modifier = Modifier.height(24.dp))
        ProfileSettingsSection(appContainer)

        Spacer(modifier = Modifier.height(24.dp))
        HistorySettingsSection(appContainer)
        Spacer(modifier = Modifier.height(24.dp))
        CloudSettingsSection(appContainer)
    }
}

private suspend fun copyUriToCacheFile(context: Context, uri: Uri): File =
    withContext(Dispatchers.IO) {
        val stagedFile = File(context.cacheDir, "staged-watch-app.apk")
        context.contentResolver.openInputStream(uri)?.use { input ->
            stagedFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open selected APK")
        stagedFile
    }
