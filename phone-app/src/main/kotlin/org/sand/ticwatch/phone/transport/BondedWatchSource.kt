package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import org.sand.ticwatch.phone.datalayer.WatchNode

/** Lists the watch the direct Bluetooth transport would talk to. */
fun interface BondedWatchSource {
    fun bondedWatch(): WatchNode?
}

/**
 * [BondedWatchSource] over the phone's Bluetooth bond list, matched the same way
 * [BluetoothClassicTransport] picks its peer. Bonded doesn't mean in range, hence
 * `isNearby = false`.
 */
class BluetoothBondedWatchSource(private val context: Context) : BondedWatchSource {

    override fun bondedWatch(): WatchNode? = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        TransportProtocol.findBondedWatch(adapter)?.let { device ->
            WatchNode(id = device.address, displayName = device.name ?: device.address, isNearby = false)
        }
    }.getOrNull()
}
