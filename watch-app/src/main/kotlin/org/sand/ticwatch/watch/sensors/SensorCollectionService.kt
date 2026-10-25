package org.sand.ticwatch.watch.sensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.buffer.SqliteSensorSampleBuffer
import org.sand.ticwatch.watch.datalayer.SensorSyncSender
import org.sand.ticwatch.watch.transport.BluetoothClassicTransport

/**
 * Foreground service that owns heart-rate collection and the bridge sync to the phone
 * for as long as the watch is powered on. Health Services' [androidx.health.services.client.MeasureClient]
 * only keeps delivering updates to an app that's in the foreground or running a
 * foreground service - collection used to be driven from a `LaunchedEffect` in
 * MainActivity, so it silently stopped the moment the screen turned off or the app was
 * swiped away. Owning it here instead is what makes a full day of samples reach the
 * phone (and from there, Health Connect) rather than only whatever happened to be
 * collected while the activity was on screen.
 */
class SensorCollectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()
    private val _lastHeartRate = MutableStateFlow<Double?>(null)

    inner class LocalBinder : Binder() {
        val lastHeartRate: StateFlow<Double?> get() = _lastHeartRate
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val sensorCollector: SensorCollector = HealthServicesSensorCollector(applicationContext)
        val sensorSyncSender = SensorSyncSender(
            transport = BluetoothClassicTransport(applicationContext),
            pendingSamples = SqliteSensorSampleBuffer(applicationContext),
        )
        // Shared once so the UI-display side and the bridge-sync side don't each open
        // their own separate MeasureClient registration for the same data type.
        val heartRateSamples = sensorCollector.heartRateSamples()
            .shareIn(serviceScope, SharingStarted.Eagerly)

        heartRateSamples.onEach { sample -> _lastHeartRate.value = sample.value }.launchIn(serviceScope)
        serviceScope.launch { sensorSyncSender.sync(heartRateSamples) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart rate sync", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TicWatch Connector")
            .setContentText("Collecting heart rate")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "sensor_collection"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SensorCollectionService::class.java))
        }
    }
}
