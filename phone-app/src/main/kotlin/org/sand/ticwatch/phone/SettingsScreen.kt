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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sand.ticwatch.phone.datalayer.WATCH_APP_PACKAGE_NAME
import org.sand.ticwatch.phone.datalayer.WatchNode
import java.io.File

/**
 * Lets the user pick a watch-app APK from local storage and push it to a paired watch
 * via [AppContainer.watchAppManager]. Reuses the same install path the dashboard would
 * use for a first install: PackageInstaller treats a matching packageName as an update.
 */
@Composable
fun SettingsScreen(appContainer: AppContainer) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var watches by remember { mutableStateOf<List<WatchNode>>(emptyList()) }
    var selectedWatchId by remember { mutableStateOf<String?>(null) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var isUpdating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        watches = appContainer.watchAppManager.listPairedWatches()
    }

    val pickApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        selectedApkUri = uri
        statusMessage = null
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Watch-app APK", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive")) }) {
            Text(text = "Select APK")
        }
        Text(text = selectedApkUri?.lastPathSegment ?: "No APK selected")

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "Target watch", style = MaterialTheme.typography.titleMedium)
        if (watches.isEmpty()) {
            Text(text = "No paired watches found.")
        } else {
            Column(modifier = Modifier.selectableGroup()) {
                watches.forEach { watch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = watch.id == selectedWatchId,
                                onClick = { selectedWatchId = watch.id },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = watch.id == selectedWatchId, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "${watch.displayName} (${if (watch.isNearby) "nearby" else "reachable"})")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            enabled = selectedApkUri != null && selectedWatchId != null && !isUpdating,
            onClick = {
                val apkUri = selectedApkUri ?: return@Button
                val watchId = selectedWatchId ?: return@Button
                isUpdating = true
                statusMessage = null
                coroutineScope.launch {
                    val result = runCatching {
                        val stagedApk = copyUriToCacheFile(context, apkUri)
                        appContainer.watchAppManager.installApp(watchId, stagedApk, WATCH_APP_PACKAGE_NAME).getOrThrow()
                    }
                    isUpdating = false
                    statusMessage = if (result.isSuccess) {
                        "Watch app updated."
                    } else {
                        "Update failed: ${result.exceptionOrNull()?.message}"
                    }
                }
            },
        ) {
            Text(text = "Update app on watch")
        }

        if (isUpdating) {
            Spacer(modifier = Modifier.height(8.dp))
            CircularProgressIndicator()
        }

        statusMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it)
        }
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
