package org.sand.ticwatch.watch.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** Where the phone reaches this watch's Wi-Fi link and the key sealing it. */
class WifiLinkEndpoint(val host: String, val port: Int, val key: ByteArray)

/**
 * Serves the link's frames over Wi-Fi while the phone sends something large. Opened on demand: it
 * requests the Wi-Fi network (which also keeps the radio up, or brings it up off the charger),
 * listens on a random port under a fresh key, and lets everything go once no connection has been
 * open for [IDLE_TIMEOUT_MS]. The key travels over the bonded RFCOMM link, so a client on the same
 * LAN without it fails authentication on its first record.
 */
class WifiLinkServer(
    context: Context,
    private val scope: CoroutineScope,
    private val serve: suspend (connection: Closeable, input: InputStream, output: OutputStream) -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val opening = Mutex()
    @Volatile private var session: Session? = null

    /** The running session's endpoint, or a new one; null when Wi-Fi didn't come up in time. */
    suspend fun open(): WifiLinkEndpoint? = opening.withLock {
        session?.takeUnless { it.closed }?.endpoint ?: startSession()
    }

    fun close() {
        session?.close()
    }

    private suspend fun startSession(): WifiLinkEndpoint? {
        val address = CompletableDeferred<Inet4Address>()
        val lost = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }?.let(address::complete)
            }

            override fun onLost(network: Network) {
                lost.complete(Unit)
                session?.close()
            }
        }
        // LAN-only is enough, so a network without internet access qualifies too.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            connectivity.requestNetwork(request, callback)
            val host = withTimeoutOrNull(WIFI_TIMEOUT_MS) { address.await() }
            if (host == null || lost.isCompleted) {
                Log.i(TAG, "Wi-Fi did not come up within $WIFI_TIMEOUT_MS ms")
                connectivity.unregisterNetworkCallback(callback)
                return null
            }
            val server = ServerSocket().apply {
                soTimeout = IDLE_TIMEOUT_MS
                bind(InetSocketAddress(host, ANY_FREE_PORT))
            }
            val key = ByteArray(TransportProtocol.WIFI_KEY_BYTES).also(SecureRandom()::nextBytes)
            val endpoint = WifiLinkEndpoint(requireNotNull(host.hostAddress), server.localPort, key)
            Session(server, callback, endpoint).also {
                session = it
                it.start()
            }
            Log.i(TAG, "Wi-Fi link listening on ${endpoint.host}:${endpoint.port}")
            endpoint
        } catch (e: IOException) {
            Log.w(TAG, "Failed to open the Wi-Fi link", e)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to request the Wi-Fi network", e)
            null
        }
    }

    private inner class Session(
        private val server: ServerSocket,
        private val callback: ConnectivityManager.NetworkCallback,
        val endpoint: WifiLinkEndpoint,
    ) {
        private val connections = AtomicInteger()
        private val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        @Volatile var closed = false
            private set

        fun start() {
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            scope.launch {
                try {
                    acceptLoop()
                } catch (e: IOException) {
                    if (!closed) Log.w(TAG, "Wi-Fi link accept failed", e)
                } finally {
                    close()
                }
            }
        }

        private fun acceptLoop() {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    if (connections.get() == 0) return
                    continue
                }
                socket.tcpNoDelay = true
                socket.soTimeout = READ_TIMEOUT_MS
                connections.incrementAndGet()
                scope.launch {
                    try {
                        serve(
                            socket,
                            SealedInputStream(socket.getInputStream(), endpoint.key, LinkDirection.PHONE_TO_WATCH),
                            SealedOutputStream(socket.getOutputStream(), endpoint.key, LinkDirection.WATCH_TO_PHONE),
                        )
                    } finally {
                        connections.decrementAndGet()
                    }
                }
            }
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            runCatching { server.close() }
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Wi-Fi link closed")
        }
    }

    private companion object {
        const val TAG = "WifiLinkServer"
        const val WAKE_LOCK_TAG = "ticwatch:wifi-link"
        const val ANY_FREE_PORT = 0

        // Associating with an access point after the radio was off takes seconds, not milliseconds.
        const val WIFI_TIMEOUT_MS = 20_000L
        const val IDLE_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000

        // Safety net only: the session releases it as soon as it closes.
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1_000L
    }
}
