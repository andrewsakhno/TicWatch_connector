package org.sand.ticwatch.watch

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.sensors.SensorCollectionService
import org.sand.ticwatch.watch.sensors.SensorType
import org.sand.ticwatch.watch.transport.BluetoothLinkServerService

class MainActivity : ComponentActivity() {

    private var lastHeartRateFlow by mutableStateOf<StateFlow<Double?>?>(null)
    private var boundToSensorService = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            lastHeartRateFlow = (binder as? SensorCollectionService.LocalBinder)?.readings?.latest(SensorType.HEART_RATE)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            lastHeartRateFlow = null
        }
    }

    // Single multi-permission request: Android serves only one permission dialog at a
    // time, so a second launch() issued right after the first is silently denied.
    private val requestStartupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { onStartupPermissionsResolved() }

    // BODY_SENSORS_BACKGROUND can only be requested on its own, after BODY_SENSORS is
    // granted. Without it, a SensorCollectionService started from the background
    // (AutoStartReceiver after reboot/update) runs but never receives heart rate,
    // because the FGS isn't given while-in-use sensor access. Launched from the
    // first request's result, so the two dialogs are sequential, not concurrent.
    private val requestBackgroundSensors = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) Log.w(TAG, "Background body-sensor access denied") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missing = startupPermissions().filterNot(::isGranted)
        if (missing.isEmpty()) {
            onStartupPermissionsResolved()
        } else {
            requestStartupPermissions.launch(missing.toTypedArray())
        }

        setContent {
            MaterialTheme {
                HeartRateScreen(lastHeartRateFlow)
            }
        }
    }

    private fun startupPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BODY_SENSORS)
        // Health Services' user-activity state (asleep/awake), the source of sleep sessions.
        add(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // Bluetooth link starts regardless (it degrades gracefully without the grant, as
    // before); sensor collection needs BODY_SENSORS.
    private fun onStartupPermissionsResolved() {
        startBluetoothLinkService()
        if (isGranted(Manifest.permission.BODY_SENSORS)) {
            startAndBindSensorCollectionService()
            requestBackgroundSensorsIfNeeded()
        }
    }

    private fun requestBackgroundSensorsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isGranted(Manifest.permission.BODY_SENSORS_BACKGROUND)) return
        requestBackgroundSensors.launch(Manifest.permission.BODY_SENSORS_BACKGROUND)
    }

    private fun startBluetoothLinkService() {
        startForegroundService(Intent(this, BluetoothLinkServerService::class.java))
    }

    private fun startAndBindSensorCollectionService() {
        SensorCollectionService.start(this)
        bindService(Intent(this, SensorCollectionService::class.java), serviceConnection, 0)
        boundToSensorService = true
    }

    override fun onDestroy() {
        if (boundToSensorService) {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatchMainActivity"
    }
}

@Composable
private fun HeartRateScreen(lastHeartRateFlow: StateFlow<Double?>?) {
    var lastBpm by remember { mutableStateOf<Double?>(null) }

    LaunchedEffect(lastHeartRateFlow) {
        lastHeartRateFlow?.collect { value -> lastBpm = value }
    }

    Scaffold {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = lastBpm?.let { "%.0f bpm".format(it) } ?: "-- bpm")
        }
    }
}
