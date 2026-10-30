package org.sand.ticwatch.phone.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/** Finds a reachable cloud server; returns its host or null. Network I/O - never call on the main thread. */
fun interface CloudServerLocator {
    suspend fun locate(): String?
}

/** True when [host] answers [CloudApi.PING] as [CloudApi.SERVICE_NAME]. */
fun interface CloudServerProbe {
    suspend fun isCloudServer(host: String): Boolean
}

class PingCloudServerProbe(private val apis: CloudApiProvider) : CloudServerProbe {
    override suspend fun isCloudServer(host: String): Boolean = try {
        apis.api(host).ping().service == CloudApi.SERVICE_NAME
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

/** The phone's IPv4 address on its active network (Wi-Fi on the home LAN), or null. */
fun interface LocalNetworkAddress {
    fun ipv4(): Inet4Address?
}

class ConnectivityLocalNetworkAddress(context: Context) : LocalNetworkAddress {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun ipv4(): Inet4Address? = connectivity.getLinkProperties(connectivity.activeNetwork)
        ?.linkAddresses
        ?.map { it.address }
        ?.filterIsInstance<Inet4Address>()
        ?.firstOrNull { it.isSiteLocalAddress }
}

/**
 * The job_searcher discovery scheme: last host that answered -> the user's manual host -> every address
 * of the phone's /24 with [CloudApi.PORT] open (short TCP connects, [SCAN_PARALLELISM] at a time), each
 * confirmed by the ping. The winner is remembered, so a scan only happens after the server moved.
 */
class SubnetCloudServerLocator(
    private val settings: CloudSettingsStore,
    private val memory: CloudConnectionMemory,
    private val probe: CloudServerProbe,
    private val localAddress: LocalNetworkAddress,
) : CloudServerLocator {

    override suspend fun locate(): String? {
        val known = listOfNotNull(memory.lastHost, settings.get().manualHost.takeIf { it.isNotBlank() }).distinct()
        val host = known.firstOrNull { probe.isCloudServer(it) } ?: scanSubnet()
        if (host != null) memory.lastHost = host
        return host
    }

    private suspend fun scanSubnet(): String? {
        val own = localAddress.ipv4() ?: return null.also { Log.i(TAG, "No local IPv4 address, skipping the scan") }
        val octets = own.address
        val prefix = (0 until PREFIX_OCTETS).joinToString(".") { (octets[it].toInt() and OCTET_MASK).toString() }
        val ownLast = octets[PREFIX_OCTETS].toInt() and OCTET_MASK
        val candidates = (FIRST_HOST..LAST_HOST).filter { it != ownLast }.map { "$prefix.$it" }
        Log.i(TAG, "Scanning $prefix.0/24 for port ${CloudApi.PORT}")
        val open = withContext(Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)) {
            coroutineScope { candidates.map { async { it.takeIf(::isPortOpen) } }.awaitAll().filterNotNull() }
        }
        return open.firstOrNull { probe.isCloudServer(it) }
            .also { Log.i(TAG, if (it == null) "No cloud server found" else "Cloud server found at $it") }
    }

    private fun isPortOpen(host: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, CloudApi.PORT), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TAG = "CloudServerLocator"
        const val PREFIX_OCTETS = 3
        const val OCTET_MASK = 0xFF
        const val FIRST_HOST = 1
        const val LAST_HOST = 254
        const val SCAN_PARALLELISM = 48
        const val CONNECT_TIMEOUT_MS = 300
    }
}
