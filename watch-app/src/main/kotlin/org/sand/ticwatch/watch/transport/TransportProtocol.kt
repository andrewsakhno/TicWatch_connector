package org.sand.ticwatch.watch.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Wire-level contract for [BluetoothClassicTransport]/`BluetoothLinkServerService` on
 * both phone-app and watch-app. Must be kept in sync manually between the two
 * independent Gradle projects, same as `datalayer/DataLayerPaths.kt`.
 *
 * Each send opens a fresh RFCOMM socket, writes `path` (UTF), then `length` (unused by
 * the reader - the payload simply runs to end-of-stream when the sender closes the
 * socket), then the payload bytes, and closes. One socket carries exactly one payload.
 */
object TransportProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7b1e6b9e-6e0a-4c9b-9e2e-6a2a2f7a9b10")
    const val SERVICE_NAME = "TicWatchConnectorLink"

    /** Finds the bonded device that looks like the phone (major device class = Phone). */
    fun findBondedPhone(adapter: BluetoothAdapter): BluetoothDevice? =
        runCatching {
            adapter.bondedDevices?.firstOrNull { device ->
                device.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE
            }
        }.getOrNull()
}
