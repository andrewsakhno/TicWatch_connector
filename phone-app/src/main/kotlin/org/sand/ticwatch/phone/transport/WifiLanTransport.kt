package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Where the watch's Wi-Fi link listens and the key sealing it; good for one short session. */
class WifiLinkEndpoint(val host: String, val port: Int, val key: ByteArray)

/** The phone's own Wi-Fi network, or null when it isn't on one. */
fun interface WifiNetworkSource {
    fun current(): Network?
}

/** Asks the watch to open its Wi-Fi link; null when it can't (no Wi-Fi, older watch-app). */
fun interface WifiEndpointRequester {
    fun request(): WifiLinkEndpoint?
}

class ConnectivityWifiNetworkSource(context: Context) : WifiNetworkSource {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    // A VPN network lists its underlying transports too, so NOT_VPN is what picks the real Wi-Fi.
    @Suppress("DEPRECATION")
    override fun current(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
    }
}

/** Negotiates over the bonded RFCOMM link, so only the paired watch can hand out an endpoint. */
class RfcommWifiEndpointRequester(private val context: Context) : WifiEndpointRequester {

    override fun request(): WifiLinkEndpoint? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: error("Bluetooth not supported on this device")
        val watch = TransportProtocol.findBondedWatch(adapter) ?: error("No bonded watch found")
        return watch.connectLinkSocket().use { socket ->
            val output = DataOutputStream(socket.outputStream)
            output.writeUTF(TransportProtocol.WIFI_OFFER_FRAME_PATH)
            output.writeLong(TransportProtocol.WIFI_OFFER_FRAME_LENGTH)
            output.flush()
            val input = DataInputStream(socket.inputStream)
            if (input.read() != TransportProtocol.WIFI_READY) return@use null
            WifiLinkEndpoint(
                host = input.readUTF(),
                port = input.readInt(),
                key = ByteArray(TransportProtocol.WIFI_KEY_BYTES).also(input::readFully),
            )
        }
    }
}

/**
 * [DeviceLinkTransport] for large payloads over the LAN both devices are on, roughly an order of
 * magnitude faster than RFCOMM. It speaks the same resumable frame, so a [FallbackDeviceLinkTransport]
 * behind it resumes over Bluetooth from the offset Wi-Fi reached. Any failure here is final for this
 * transport: the fallback is the retry.
 */
class WifiLanTransport(
    private val networks: WifiNetworkSource,
    private val endpoints: WifiEndpointRequester,
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val network = networks.current() ?: throw IOException("Phone is not on Wi-Fi")
                val endpoint = endpoints.request() ?: throw IOException("Watch offered no Wi-Fi link")
                val upload = ResumableUpload(path, length, source, source.contentHash())
                val preemption = currentLinkPreemption()
                val startedAt = SystemClock.elapsedRealtime()
                while (true) {
                    // Commands still travel over RFCOMM, but the arbiter's link is ours until we yield it.
                    if (preemption?.isRequested == true) preemption.yieldLink()
                    val outcome = connect(network, endpoint).use { socket ->
                        upload.runOn(
                            SealedInputStream(socket.getInputStream(), endpoint.key, LinkDirection.WATCH_TO_PHONE),
                            SealedOutputStream(socket.getOutputStream(), endpoint.key, LinkDirection.PHONE_TO_WATCH),
                        ) { preemption?.isRequested == true }
                    }
                    when (outcome) {
                        ResumableUpload.Outcome.COMPLETED -> break
                        ResumableUpload.Outcome.PAUSED -> Log.i(TAG, "Pausing $path at ${upload.confirmedOffset}/$length")
                        ResumableUpload.Outcome.PEER_IS_LEGACY -> throw IOException("Wi-Fi peer rejected the resumable frame")
                    }
                }
                Log.i(TAG, "Sent $path ($length bytes) over Wi-Fi in ${SystemClock.elapsedRealtime() - startedAt} ms")
                Unit
            }
        }

    private fun connect(network: Network, endpoint: WifiLinkEndpoint): Socket =
        network.socketFactory.createSocket().apply {
            try {
                tcpNoDelay = true
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                runCatching { close() }
                throw e
            }
        }

    private companion object {
        const val TAG = "WifiLanTransport"
        const val CONNECT_TIMEOUT_MS = 3_000

        // Also covers the final reply, which waits for the watch to hash and stage the whole payload.
        const val READ_TIMEOUT_MS = 30_000
    }
}

/** Sends [LinkPriority.BULK] payloads through [large], everything else through [regular]. */
class LargePayloadRoutingTransport(
    private val large: DeviceLinkTransport,
    private val regular: DeviceLinkTransport,
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> =
        when (LinkPriority.of(length)) {
            LinkPriority.BULK -> large.send(path, length, source)
            LinkPriority.INTERACTIVE -> regular.send(path, length, source)
        }
}
