package org.sand.ticwatch.phone.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.datalayer.PhoneMessageRouter
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
        )
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        scope.launch { acceptLoop() }
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "watch_link_bridge"
        private const val TAG = "BluetoothLinkServer"
    }
}
