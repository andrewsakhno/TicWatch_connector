package org.sand.ticwatch.watch.transport

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
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.WatchMessageRouter
import org.sand.ticwatch.watch.install.PackageInstallerApkInstaller
import org.sand.ticwatch.watch.sensors.HealthServicesSensorCollector
import java.io.DataInputStream

/**
 * Long-running counterpart to [BluetoothClassicTransport] on the phone side: listens
 * for incoming RFCOMM connections and forwards each one-shot frame into
 * [WatchMessageRouter]. Needed because, unlike the Wear Data Layer, a raw Bluetooth
 * socket has no OS-mediated wake-up - something has to be listening continuously.
 */
class BluetoothLinkServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: BluetoothServerSocket? = null

    private val router: WatchMessageRouter by lazy {
        WatchMessageRouter(
            context = applicationContext,
            apkInstaller = PackageInstallerApkInstaller(applicationContext),
            sensorCollector = HealthServicesSensorCollector(applicationContext),
            replyTransport = TransportFactory.create(applicationContext),
            scope = scope,
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
                if (path == DataLayerPaths.CHANNEL_APK_TRANSFER) {
                    router.handleIncomingApk(payload)
                } else {
                    router.handleMessage(path, payload.readBytes())
                }
                // Ack so the sender knows it's safe to close its end of the socket.
                it.outputStream.write(1)
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
            .setContentText("Listening for phone requests over Bluetooth")
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
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "watch_link_bridge"
        private const val TAG = "BluetoothLinkServer"
    }
}
