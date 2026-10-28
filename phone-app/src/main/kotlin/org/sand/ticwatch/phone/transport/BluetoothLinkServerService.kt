package org.sand.ticwatch.phone.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.datalayer.PhoneMessageRouter
import org.sand.ticwatch.phone.datalayer.WatchReplies
import org.sand.ticwatch.phone.sync.SyncStatusRepository
import java.io.DataInputStream

/**
 * Long-running counterpart to [BluetoothClassicTransport] on the watch side: listens
 * for incoming RFCOMM connections and forwards each one-shot message into
 * [PhoneMessageRouter]. Needed because, unlike the Wear Data Layer, a raw Bluetooth
 * socket has no OS-mediated wake-up - something has to be listening continuously.
 */
class BluetoothLinkServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null

    private val appContainer by lazy { AppContainer(applicationContext) }
    private val router by lazy {
        PhoneMessageRouter(
            appContainer.sensorDataSyncWorker,
            appContainer.workoutRouteRecorder,
            appContainer.exerciseRouteLog,
            SyncStatusRepository,
            WatchReplies,
            appContainer.watchStatePublishers,
            appContainer.bloodPressureRequestHandler,
            appContainer.watchSettingsSync,
            appContainer.snoreMonitorControl,
            appContainer.watchScreenshotSink,
            appContainer.snapshotResultReceiver,
        )
    }

    // Hosted here because this connectedDevice service already keeps the process alive for the watch link.
    private val cuffClient by lazy { appContainer.bloodPressureCuffClient(scope) }

    // Hosted here for the microphone foreground type this service adds when started from the foreground.
    private val snoreMonitor by lazy { appContainer.phoneSnoreMonitor(scope) }

    override fun onCreate() {
        super.onCreate()
        // Connected device only: a microphone type is refused when started from the background (boot), see onStartCommand.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        scope.launch { acceptLoop() }
        cuffClient.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // Off the main thread: the first settings read hits the preferences file.
            ACTION_START_SNORE_MONITOR -> scope.launch { snoreMonitor.start() }
            ACTION_STOP_SNORE_MONITOR -> snoreMonitor.stop()
            else -> addMicrophoneForegroundType()
        }
        return START_STICKY
    }

    /**
     * The phone's snoring analysis needs while-in-use microphone access, which a foreground service only gets when
     * its microphone type is added while the app is in the foreground (MainActivity's start). From the background
     * (boot, the watch's sleep message) Android 14 refuses it - snoring is then not measured until the app is opened.
     */
    private fun addMicrophoneForegroundType() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Microphone foreground type refused; snoring won't be measured on the phone", error)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Microphone foreground type refused; snoring won't be measured on the phone", error)
        }
    }

    private suspend fun acceptLoop() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Log.w(TAG, "No Bluetooth adapter available")
            return
        }
        val socket = runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord(TransportProtocol.SERVICE_NAME, TransportProtocol.SERVICE_UUID)
        }.onFailure { Log.w(TAG, "Failed to open RFCOMM listening socket", it) }.getOrNull() ?: return
        serverSocket = socket
        Log.i(TAG, "Listening for Bluetooth link connections")

        while (scope.isActive) {
            val connection = runCatching { socket.accept() }
                .onFailure { Log.w(TAG, "accept() failed, stopping accept loop", it) }
                .getOrNull() ?: break
            Log.i(TAG, "Accepted Bluetooth link connection from ${connection.remoteDevice?.address}")
            scope.launch { handleConnection(connection) }
        }
    }

    private suspend fun handleConnection(socket: BluetoothSocket) {
        runCatching {
            socket.use {
                val input = DataInputStream(it.inputStream)
                val path = input.readUTF()
                val length = input.readLong()
                val payload = BoundedInputStream(input, length)
                val handled = router.handleMessage(path, payload.readBytes())
                // ACK only once handled, so the watch keeps (and later resends) anything
                // that didn't make it into Health Connect instead of deleting it.
                it.outputStream.write(if (handled) TransportProtocol.ACK else TransportProtocol.NACK)
                it.outputStream.flush()
            }
        }.onFailure { Log.w(TAG, "Failed to handle incoming Bluetooth connection", it) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch link bridge", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TicWatch Connector")
            .setContentText("Listening for watch requests over Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { serverSocket?.close() }
        cuffClient.stop()
        snoreMonitor.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_SNORE_MONITOR = "org.sand.ticwatch.phone.action.START_SNORE_MONITOR"
        const val ACTION_STOP_SNORE_MONITOR = "org.sand.ticwatch.phone.action.STOP_SNORE_MONITOR"

        fun intent(context: Context, action: String): Intent = Intent(context, BluetoothLinkServerService::class.java).setAction(action)

        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "watch_link_bridge"
        private const val TAG = "BluetoothLinkServer"
    }
}
