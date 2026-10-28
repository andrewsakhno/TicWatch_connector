package org.sand.ticwatch.watch

import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.snapshot.SnapshotState
import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.sand.ticwatch.watch.screenshot.DashboardPageNavigator
import org.sand.ticwatch.watch.screenshot.VisibleScreen
import org.sand.ticwatch.watch.screenshot.WindowScreenCapturer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutMode
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutSettings
import org.sand.ticwatch.watch.autoworkout.SharedPreferencesAutoWorkoutSettings
import org.sand.ticwatch.watch.phonedata.PhonePayloadStores
import org.sand.ticwatch.watch.sensors.LiveReadings
import org.sand.ticwatch.watch.sensors.SensorCollectionService
import org.sand.ticwatch.watch.settings.SharedPreferencesWatchSettingsStore
import org.sand.ticwatch.watch.settings.WatchSettings
import org.sand.ticwatch.watch.settings.WatchSettingsSync
import org.sand.ticwatch.watch.transport.BluetoothLinkServerService

class MainActivity : ComponentActivity() {

    private var liveReadings by mutableStateOf<LiveReadings?>(null)
    private var autoWorkoutMode by mutableStateOf<AutoWorkoutMode?>(null)
    private var phonePayloads by mutableStateOf<PhonePayloadStores?>(null)
    private var bloodPressureAvailable by mutableStateOf(false)
    private var snapshotState by mutableStateOf<StateFlow<SnapshotState>?>(null)
    private val autoWorkoutSettings: AutoWorkoutSettings by lazy { SharedPreferencesAutoWorkoutSettings(applicationContext) }
    private var watchSettings by mutableStateOf<WatchSettings?>(null)
    private val watchSettingsSync by lazy { WatchSettingsSync.create(applicationContext, lifecycleScope) }
    private var boundToSensorService = false
    private var sensorService: SensorCollectionService.LocalBinder? = null
    private var holdingLiveHeartRate = false
    private var visible = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            sensorService = binder as? SensorCollectionService.LocalBinder
            liveReadings = sensorService?.readings
            bloodPressureAvailable = sensorService != null
            snapshotState = sensorService?.snapshotState
            updateLiveHeartRateDemand()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sensorService = null
            holdingLiveHeartRate = false
            liveReadings = null
            bloodPressureAvailable = false
            snapshotState = null
        }
    }

    // Single multi-permission request: Android serves only one permission dialog at a
    // time, so a second launch() issued right after the first is silently denied.
    private val requestStartupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onStartupPermissionsResolved() }

    // Background permissions (BODY_SENSORS_BACKGROUND, ACCESS_BACKGROUND_LOCATION) can only be
    // requested one at a time, after their foreground counterpart is granted. Without them a
    // SensorCollectionService started from the background (AutoStartReceiver after
    // reboot/update, or a workout starting off-screen) gets no while-in-use sensor/location
    // access. Each is launched from the previous result, so the dialogs are sequential.
    private val pendingBackgroundPermissions = ArrayDeque<String>()

    private val requestBackgroundPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) Log.w(TAG, "Background permission denied: ${pendingBackgroundPermissions.firstOrNull()}")
        pendingBackgroundPermissions.removeFirstOrNull()
        requestNextBackgroundPermission()
    }

    /** Lets the debug bridge switch pages and capture them while this screen is visible. */
    private val pageNavigator = DashboardPageNavigator()
    private val screenCapturer by lazy { WindowScreenCapturer(window, pageNavigator) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = startupPermissions().filterNot(::isGranted)
        if (missing.isEmpty()) {
            onStartupPermissionsResolved()
        } else {
            requestStartupPermissions.launch(missing.toTypedArray())
        }

        // First SharedPreferences access reads the file: off the main thread.
        lifecycleScope.launch {
            autoWorkoutMode = withContext(Dispatchers.IO) { autoWorkoutSettings.mode() }
            phonePayloads = withContext(Dispatchers.IO) { PhonePayloadStores.get(applicationContext) }
            val settingsStore = withContext(Dispatchers.IO) { SharedPreferencesWatchSettingsStore.get(applicationContext) }
            // A change from the phone shows up here too.
            settingsStore.settings.collect { watchSettings = it }
        }

        setContent {
            MaterialTheme {
                val stores = phonePayloads
                WatchDashboardScreen(
                    readings = liveReadings,
                    healthScores = stores?.healthScores?.latest,
                    spaceWeather = stores?.spaceWeather?.latest,
                    bloodPressure = BloodPressurePageState(
                        history = stores?.bloodPressureHistory?.latest,
                        lastResult = stores?.bloodPressureResult?.latest,
                        onMeasure = if (bloodPressureAvailable) ::requestBloodPressure else null,
                        autoMeasure = watchSettings?.autoBloodPressure,
                        onAutoMeasureChange = ::setAutoBloodPressure,
                    ),
                    autoWorkoutMode = autoWorkoutMode,
                    onAutoWorkoutModeClick = ::cycleAutoWorkoutMode,
                    logCapture = watchSettings?.logCapture,
                    onLogCaptureChange = ::setLogCapture,
                    snoreAnalysis = SnoreAnalysisState(
                        enabled = watchSettings?.snoreAnalysis,
                        microphone = watchSettings?.snoreMicrophone,
                        onEnabledChange = { on -> changeSettings { it.copy(snoreAnalysis = on) } },
                        onMicrophoneClick = { changeSettings { it.copy(snoreMicrophone = it.snoreMicrophone.next()) } },
                    ),
                    snapshot = SnapshotPageState(
                        state = snapshotState,
                        onCapture = if (snapshotState != null) ::requestSnapshot else null,
                    ),
                    pageNavigator = pageNavigator,
                )
            }
        }
    }

    private fun requestSnapshot() {
        sensorService?.snapshot?.request(null)
    }

    private fun requestBloodPressure() {
        sensorService?.bloodPressure?.request()
    }

    private fun setAutoBloodPressure(enabled: Boolean) = changeSettings { it.copy(autoBloodPressure = enabled) }

    private fun setLogCapture(enabled: Boolean) = changeSettings { it.copy(logCapture = enabled) }

    /** Stored and sent to the phone; the screen follows through the store's flow. */
    private fun changeSettings(transform: (WatchSettings) -> WatchSettings) {
        lifecycleScope.launch(Dispatchers.IO) { watchSettingsSync.change(transform) }
    }

    private fun cycleAutoWorkoutMode() {
        val next = autoWorkoutMode?.next() ?: return
        autoWorkoutMode = next
        lifecycleScope.launch(Dispatchers.IO) { autoWorkoutSettings.setMode(next) }
    }

    private fun startupPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BODY_SENSORS)
        // Health Services' user-activity state (asleep/awake), the source of sleep sessions.
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        // Snore detection during sleep; optional, collection runs without it.
        add(Manifest.permission.RECORD_AUDIO)
        // The watch's own GPS route during workouts; optional, the phone still tracks one.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // Bluetooth link starts regardless (it degrades gracefully without the grant, as
    // before); sensor collection needs BODY_SENSORS.
    private fun onStartupPermissionsResolved() {
        startBluetoothLinkService()
        if (isGranted(Manifest.permission.BODY_SENSORS)) startAndBindSensorCollectionService()
        pendingBackgroundPermissions.clear()
        pendingBackgroundPermissions += backgroundPermissions().filterNot(::isGranted)
        requestNextBackgroundPermission()
    }

    /** Background permissions whose foreground counterpart is already granted (Android refuses the rest). */
    private fun backgroundPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isGranted(Manifest.permission.BODY_SENSORS)) {
            add(Manifest.permission.BODY_SENSORS_BACKGROUND)
        }
        if (isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun requestNextBackgroundPermission() {
        val next = pendingBackgroundPermissions.firstOrNull() ?: return
        requestBackgroundPermission.launch(next)
    }

    private fun startBluetoothLinkService() {
        startForegroundService(Intent(this, BluetoothLinkServerService::class.java))
    }

    private fun startAndBindSensorCollectionService() {
        SensorCollectionService.start(this)
        bindService(Intent(this, SensorCollectionService::class.java), serviceConnection, 0)
        boundToSensorService = true
    }

    override fun onStart() {
        super.onStart()
        visible = true
        VisibleScreen.show(screenCapturer)
        updateLiveHeartRateDemand()
    }

    override fun onStop() {
        visible = false
        VisibleScreen.hide(screenCapturer)
        updateLiveHeartRateDemand()
        super.onStop()
    }

    /** Live (per-second) heart rate only while this screen is visible; otherwise the passive batches suffice. */
    private fun updateLiveHeartRateDemand() {
        val demand = sensorService?.liveHeartRate ?: return
        if (visible == holdingLiveHeartRate) return
        if (visible) demand.acquire() else demand.release()
        holdingLiveHeartRate = visible
    }

    override fun onDestroy() {
        if (holdingLiveHeartRate) sensorService?.liveHeartRate?.release()
        if (boundToSensorService) {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchMainActivity"
    }
}
