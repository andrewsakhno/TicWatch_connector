package org.sand.ticwatch.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.datalayer.WatchNode
import org.sand.ticwatch.phone.history.BodyTemperatureBackfillWorker
import org.sand.ticwatch.phone.history.SensorHistoryPurgeWorker
import org.sand.ticwatch.phone.sync.SyncDirection
import org.sand.ticwatch.phone.sync.SyncTransfer
import org.sand.ticwatch.phone.sync.SyncStatusRepository
import org.sand.ticwatch.phone.transport.BluetoothLinkServerService
import kotlin.math.roundToInt

private enum class Screen(val label: String) {
    Dashboard("Dashboard"),
    Settings("Settings"),
    McpAgent("MCP agent"),
}

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    // Plain OS runtime-permission dialog for these (declared as ordinary
    // <uses-permission> entries in the manifest) rather than Health Connect's own
    // PermissionController.createRequestPermissionResultContract() - both were
    // verified on-device to present the real consent screen and report a full
    // grant, yet HealthConnectClient.insertRecords() still throws SecurityException
    // requiring the same permission right after. That's Health Connect's grant
    // state and its write-time enforcement disagreeing on this device (observed on
    // a Pixel running a beta OS channel) - a platform issue, not something either
    // contract can work around from here.
    // Bluetooth and Health Connect go in one request: Android serves only one
    // permission dialog at a time, so a second launch() right after the first is
    // silently denied.
    private val requestStartupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val healthGrants = granted.filterKeys { it in appContainer.healthRecordWriter.requiredPermissions }
        if (!healthGrants.values.all { it }) {
            Log.w(TAG, "Health Connect permissions not fully granted: $healthGrants")
        }
        startBluetoothLinkService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer = AppContainer(applicationContext)
        SensorHistoryPurgeWorker.schedule(applicationContext)
        BodyTemperatureBackfillWorker.enqueue(applicationContext)

        val startupPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            // Without it the MCP agent's foreground notification (and its "Turn off" action) is hidden.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Phone GPS track of watch workouts; background location can't be asked for in the same dialog.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            addAll(appContainer.healthRecordWriter.requiredPermissions)
        }
        requestStartupPermissions.launch(startupPermissions.toTypedArray())

        val initialScreen = intent.getStringExtra(EXTRA_SCREEN)
            ?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?: Screen.Dashboard

        setContent {
            MaterialTheme {
                Surface {
                    AppScaffold(appContainer, initialScreen)
                }
            }
        }
    }

    private fun startBluetoothLinkService() {
        startForegroundService(Intent(this, BluetoothLinkServerService::class.java))
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_SCREEN = "org.sand.ticwatch.phone.extra.SCREEN"

        /** Opens the app straight on the MCP agent screen (the agent notification's tap target). */
        fun mcpAgentIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SCREEN, Screen.McpAgent.name)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(appContainer: AppContainer, initialScreen: Screen) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Screen.entries.forEach { screen ->
                    NavigationDrawerItem(
                        label = { Text(text = screen.label) },
                        selected = screen == currentScreen,
                        onClick = {
                            currentScreen = screen
                            coroutineScope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = "TicWatch Connector") },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                )
            },
        ) { innerPadding ->
            Surface(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    Screen.Dashboard -> PairedWatchesScreen(appContainer)
                    Screen.Settings -> SettingsScreen(appContainer)
                    Screen.McpAgent -> McpAgentScreen(appContainer)
                }
            }
        }
    }
}

@Composable
private fun PairedWatchesScreen(appContainer: AppContainer) {
    var watches by remember { mutableStateOf<List<WatchNode>>(emptyList()) }
    val transfers by SyncStatusRepository.transfers.collectAsState()

    LaunchedEffect(Unit) {
        watches = appContainer.watchAppManager.listPairedWatches()
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "TicWatch Connector", style = MaterialTheme.typography.headlineSmall)
        transfers.forEach { SyncProgressIndicator(it) }
        if (watches.isEmpty()) {
            Text(text = "No paired watches found.")
        } else {
            watches.forEach { watch ->
                Text(text = "${watch.displayName} (${if (watch.isNearby) "nearby" else "reachable"})")
            }
        }
    }
}

/**
 * Shows what's currently moving over the phone<->watch link and where it's headed, so
 * a multi-minute APK transfer or a sensor-data push isn't silently invisible on the
 * Dashboard. Determinate when [SyncTransfer.totalBytes] is known (Bluetooth
 * sends know the payload size upfront), indeterminate otherwise.
 */
@Composable
private fun SyncProgressIndicator(status: SyncTransfer) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val arrow = if (status.direction == SyncDirection.TO_WATCH) "Phone -> Watch" else "Watch -> Phone"
        Text(text = "$arrow: ${status.description}", style = MaterialTheme.typography.bodyMedium)

        val total = status.totalBytes
        val fraction = status.fraction
        if (total != null && fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Text(
                text = "${formatBytes(status.bytesTransferred)} / ${formatBytes(total)} (${(fraction * 100).roundToInt()}%)",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
