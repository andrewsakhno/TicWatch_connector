package org.sand.ticwatch.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.cloud.CloudSyncWorker
import org.sand.ticwatch.phone.deviation.DeviationEpisodesStore
import org.sand.ticwatch.phone.history.BodyTemperatureBackfillWorker
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.history.SensorHistoryPurgeWorker
import org.sand.ticwatch.phone.snore.ProcessAppActivity
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherRefreshWorker
import org.sand.ticwatch.phone.sync.SyncDirection
import org.sand.ticwatch.phone.sync.SyncStatusRepository
import org.sand.ticwatch.phone.sync.SyncTransfer
import org.sand.ticwatch.phone.transport.BluetoothLinkServerService
import org.sand.ticwatch.phone.ui.LOAD_ONCE
import org.sand.ticwatch.phone.ui.ReloadingStore
import org.sand.ticwatch.phone.ui.TicWatchTheme
import org.sand.ticwatch.phone.ui.anyChangeOf
import org.sand.ticwatch.phone.ui.rememberStore

private enum class Screen(@StringRes val label: Int) {
    Dashboard(R.string.screen_dashboard),
    Deviations(R.string.screen_deviations),
    Settings(R.string.screen_settings),
    McpAgent(R.string.screen_mcp_agent),
}

class MainActivity : AppCompatActivity() {

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
        SpaceWeatherRefreshWorker.schedule(applicationContext)
        CloudSyncWorker.schedule(applicationContext)
        CloudSyncWorker.requestSoon(applicationContext)
        BodyTemperatureBackfillWorker.enqueue(applicationContext)
        appContainer.liveWatchSync.bindTo(this)

        val startupPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            // Without it the MCP agent's foreground notification (and its "Turn off" action) is hidden.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Phone GPS track of watch workouts; background location can't be asked for in the same dialog.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Snoring analysis on the phone's microphone; the link service adds its microphone type once granted.
            add(Manifest.permission.RECORD_AUDIO)
            addAll(appContainer.healthRecordWriter.requiredPermissions)
        }
        requestStartupPermissions.launch(startupPermissions.toTypedArray())

        val initialScreen = intent.getStringExtra(EXTRA_SCREEN)
            ?.let { name -> Screen.entries.firstOrNull { it.name == name } }
            ?: Screen.Dashboard
        val openCardioAdvice = intent.getBooleanExtra(EXTRA_CARDIO_ADVICE, false)
        val openAdvisor = intent.getBooleanExtra(EXTRA_ADVISOR, false)

        setContent {
            TicWatchTheme {
                Surface {
                    AppScaffold(appContainer, initialScreen, openCardioAdvice, openAdvisor)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app counts as being up: the phone snore monitor ends its night on it.
        ProcessAppActivity.markActive(SensorHistoryModule.clock.instant())
    }

    private fun startBluetoothLinkService() {
        startForegroundService(Intent(this, BluetoothLinkServerService::class.java))
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_SCREEN = "org.sand.ticwatch.phone.extra.SCREEN"
        private const val EXTRA_CARDIO_ADVICE = "org.sand.ticwatch.phone.extra.CARDIO_ADVICE"
        private const val EXTRA_ADVISOR = "org.sand.ticwatch.phone.extra.ADVISOR"

        /** Opens the Dashboard's Advisor tab (the advisor widget's tap target). */
        fun advisorIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SCREEN, Screen.Dashboard.name)
                .putExtra(EXTRA_ADVISOR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** Opens the app straight on the MCP agent screen (the agent notification's tap target). */
        fun mcpAgentIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SCREEN, Screen.McpAgent.name)

        /** Opens the screen asking to explain deviation episodes (their notification's tap target). */
        fun deviationsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SCREEN, Screen.Deviations.name)

        /** Opens the Blood Pressure tab with the cardio advice sheet (the geomagnetic alert's tap target). */
        fun cardioAdviceIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SCREEN, Screen.Dashboard.name)
                .putExtra(EXTRA_CARDIO_ADVICE, true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(appContainer: AppContainer, initialScreen: Screen, openCardioAdvice: Boolean, openAdvisor: Boolean) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    var showCardioAdvice by remember { mutableStateOf(openCardioAdvice) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val deviations = rememberStore(appContainer) { scope, io ->
        DeviationEpisodesStore(scope, io, appContainer.deviationEpisodeLog, appContainer.deviationAnswers)
    }
    val waitingDeviations = deviations.state.collectAsState().value.waitingCount

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Swipe must not open the drawer (it clashes with horizontal gestures); only the hamburger does.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Screen.entries.forEach { screen ->
                    NavigationDrawerItem(
                        label = { Text(text = stringResource(screen.label)) },
                        selected = screen == currentScreen,
                        onClick = {
                            currentScreen = screen
                            coroutineScope.launch { drawerState.close() }
                        },
                        badge = {
                            if (screen == Screen.Deviations && waitingDeviations > 0) Text(text = waitingDeviations.toString())
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.app_name)) },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                        }
                    },
                    actions = {
                        CloudSyncIndicator(appContainer)
                        WatchBatteryIndicator(appContainer, SensorHistoryModule.clock)
                    },
                )
            },
        ) { innerPadding ->
            Surface(modifier = Modifier.padding(innerPadding)) {
                when (currentScreen) {
                    Screen.Dashboard -> DashboardScreen(
                        appContainer,
                        when {
                            openCardioAdvice -> DashboardTab.BLOOD_PRESSURE
                            openAdvisor -> DashboardTab.ADVISOR
                            else -> DashboardTab.METRICS
                        },
                    )
                    Screen.Deviations -> DeviationEpisodesScreen(deviations, SensorHistoryModule.zone)
                    Screen.Settings -> SettingsScreen(appContainer)
                    Screen.McpAgent -> McpAgentScreen(appContainer)
                }
            }
        }
    }
    if (showCardioAdvice) {
        CardioAdviceBottomSheet(
            appContainer.spaceWeatherStore, appContainer.geomagneticAlertPolicy, appContainer.cardioAdvisor,
        ) { showCardioAdvice = false }
    }
}

private enum class DashboardTab(@StringRes val label: Int) {
    METRICS(R.string.tab_metrics),
    ADVISOR(R.string.tab_advisor),
    BLOOD_PRESSURE(R.string.tab_blood_pressure),
    SYSTEM(R.string.tab_system),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(appContainer: AppContainer, initialTab: DashboardTab) {
    var selectedTab by remember { mutableStateOf(initialTab) }

    Column {
        PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
            DashboardTab.entries.forEach { tab ->
                Tab(
                    selected = tab == selectedTab,
                    onClick = { selectedTab = tab },
                    text = { Text(text = stringResource(tab.label)) },
                )
            }
        }
        when (selectedTab) {
            DashboardTab.METRICS -> HealthInsightsScreen(appContainer)
            DashboardTab.ADVISOR -> CardioAdvisorScreen(
                appContainer.cardioReadiness,
                anyChangeOf(appContainer.historyChanges, appContainer.bloodPressureChanges, appContainer.cloudHistoryArrivals),
            )
            DashboardTab.BLOOD_PRESSURE -> BloodPressureScreen(
                appContainer.bloodPressureMonitor, appContainer.watchSyncRequester, appContainer.cuffStatusStore,
                appContainer.medicationTracking, appContainer.cardioReportComposer, appContainer.ecgDependencies,
                anyChangeOf(appContainer.bloodPressureChanges, appContainer.historyChanges, appContainer.cloudHistoryArrivals),
            )
            DashboardTab.SYSTEM -> PairedWatchesScreen(appContainer)
        }
    }
}

@Composable
private fun PairedWatchesScreen(appContainer: AppContainer) {
    // Watch updates start here: they get the link to themselves.
    DisposableEffect(appContainer) {
        appContainer.liveWatchSync.setPaused(true)
        onDispose { appContainer.liveWatchSync.setPaused(false) }
    }
    val store = rememberStore(appContainer) { scope, io ->
        ReloadingStore(scope, io, LOAD_ONCE, appContainer.watchAppManager::listPairedWatches)
    }
    val watches = store.state.collectAsState().value.orEmpty()
    val transfers by SyncStatusRepository.transfers.collectAsState()

    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        transfers.forEach { SyncProgressIndicator(it) }
        if (watches.isEmpty()) {
            Text(text = stringResource(R.string.no_paired_watches))
        } else {
            watches.forEach { watch ->
                Text(text = stringResource(if (watch.isNearby) R.string.watch_nearby else R.string.watch_reachable, watch.displayName))
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
        val direction = if (status.direction == SyncDirection.TO_WATCH) R.string.sync_phone_to_watch else R.string.sync_watch_to_phone
        Text(text = stringResource(direction, stringResource(status.description)), style = MaterialTheme.typography.bodyMedium)

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
