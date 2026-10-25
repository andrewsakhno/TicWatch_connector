package org.sand.ticwatch.phone.mcp

import java.net.Inet4Address
import java.net.NetworkInterface

/** Lists this device's non-loopback IPv4 addresses, i.e. what an external client on the same network can dial. */
object LocalAddressProvider {
    fun ipv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
