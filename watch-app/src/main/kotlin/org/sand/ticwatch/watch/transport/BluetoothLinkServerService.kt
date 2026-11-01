package org.sand.ticwatch.watch.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.R
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.datalayer.WatchMessageRouter
import org.sand.ticwatch.watch.datalayer.defaultDebugProbeRoutes
import org.sand.ticwatch.watch.install.PackageInstallerApkInstaller
import org.sand.ticwatch.watch.sensors.HealthServicesSensorCollector
import org.sand.ticwatch.watch.sensors.SensorCollectionService

/**
 * Long-running counterpart to [BluetoothClassicTransport] on the phone side: listens
 * for incoming RFCOMM connections and forwards each one-shot frame into
 * [WatchMessageRouter]. Needed because, unlike the Wear Data Layer, a raw Bluetooth
 * socket has no OS-mediated wake-up - something has to be listening continuously.
 */
class BluetoothLinkServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null

    private val resumableReceiver: ResumableTransferReceiver by lazy {
        ResumableTransferReceiver(File(noBackupFilesDir, INCOMING_TRANSFERS_DIR))
    }

    private val wifiLink: WifiLinkServer by lazy {
        WifiLinkServer(applicationContext, scope, ::serveFrames)
    }

    private val router: WatchMessageRouter by lazy {
        WatchMessageRouter(
            context = applicationContext,
            apkInstaller = PackageInstallerApkInstaller(applicationContext),
            sensorCollector = HealthServicesSensorCollector(applicationContext),
            debugProbes = defaultDebugProbeRoutes(applicationContext),
            replyTransport = TransportFactory.create(applicationContext),
            phonePayloads = WatchMessageRouter.phonePayloadSinks(applicationContext, scope),
            snapshotRequester = SensorCollectionService.snapshotRequester(applicationContext),
            syncNowRequester = SensorCollectionService.syncNowRequester(applicationContext),
            ecgRequester = SensorCollectionService.ecgRequester(applicationContext),
            scope = scope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureListening()
        return START_STICKY
    }

    /**
     * Re-arms the listener on every start: a Bluetooth reset kills the server socket while
     * the foreground service itself stays alive, so `onCreate` alone would never listen again.
     */
    private fun ensureListening() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch { listenLoop() }
    }

    private suspend fun listenLoop() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) {
            Log.w(TAG, "No Bluetooth adapter available")
            return
        }
        while (scope.isActive) {
            acceptLoop(adapter)
            runCatching { serverSocket?.close() }
            serverSocket = null
            delay(RELISTEN_DELAY_MS)
        }
    }

    private fun acceptLoop(adapter: BluetoothAdapter) {
        val socket = runCatching {
            adapter.listenUsingInsecureRfcommWithServiceRecord(TransportProtocol.SERVICE_NAME, TransportProtocol.SERVICE_UUID)
        }.onFailure { Log.w(TAG, "Failed to open RFCOMM listening socket, retrying", it) }.getOrNull() ?: return
        serverSocket = socket
        Log.i(TAG, "Listening for Bluetooth link connections")

        while (scope.isActive) {
            val connection = runCatching { socket.accept() }
                .onFailure { Log.w(TAG, "accept() failed, re-opening the listening socket", it) }
                .getOrNull() ?: return
            Log.i(TAG, "Accepted Bluetooth link connection from ${connection.remoteDevice?.address}")
            scope.launch { handleConnection(connection) }
        }
    }

    private suspend fun handleConnection(socket: BluetoothSocket) {
        serveFrames(socket, socket.inputStream, socket.outputStream)
    }

    /** Runs the link's frames on one connection, RFCOMM or Wi-Fi, and closes it when they end. */
    private suspend fun serveFrames(connection: Closeable, rawInput: InputStream, rawOutput: OutputStream) {
        runCatching {
            connection.use {
                val input = DataInputStream(rawInput)
                val output = DataOutputStream(rawOutput)
                var path = input.readUTF()
                while (true) {
                    val length = input.readLong()
                    if (path == TransportProtocol.RESUMABLE_FRAME_PATH) {
                        // The receiver writes its own per-chunk and final replies.
                        resumableReceiver.receive(connection, input, output) { realPath, file ->
                            file.inputStream().use { payload -> dispatch(realPath, payload) }
                        }
                        return@use
                    }
                    if (path == TransportProtocol.WIFI_OFFER_FRAME_PATH) {
                        answerWifiOffer(output)
                    } else {
                        dispatch(path, BoundedInputStream(input, length))
                        // Ack so the sender knows it's safe to close its end of the socket.
                        output.writeByte(TransportProtocol.ACK)
                        output.flush()
                    }
                    // A sender holding the link sends its next frame here; any other has closed the socket by now.
                    path = runCatching { input.readUTF() }.getOrNull() ?: return@use
                }
            }
        }.onFailure { Log.w(TAG, "Failed to handle incoming link connection", it) }
    }

    private suspend fun answerWifiOffer(output: DataOutputStream) {
        val endpoint = wifiLink.open()
        if (endpoint == null) {
            output.writeByte(TransportProtocol.NACK)
        } else {
            output.writeByte(TransportProtocol.WIFI_READY)
            output.writeUTF(endpoint.host)
            output.writeInt(endpoint.port)
            output.write(endpoint.key)
        }
        output.flush()
    }

    private suspend fun dispatch(path: String, payload: InputStream) {
        if (path == DataLayerPaths.CHANNEL_APK_TRANSFER) {
            router.handleIncomingApk(payload)
        } else {
            router.handleMessage(path, payload.readBytes())
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.bridge_channel), NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.bridge_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { serverSocket?.close() }
        wifiLink.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "watch_link_bridge"
        private const val TAG = "BluetoothLinkServer"
        private const val INCOMING_TRANSFERS_DIR = "incoming_transfers"
        // Short on purpose; a suspended watch only postpones it and onStartCommand re-arms anyway.
        private const val RELISTEN_DELAY_MS = 3_000L
    }
}
