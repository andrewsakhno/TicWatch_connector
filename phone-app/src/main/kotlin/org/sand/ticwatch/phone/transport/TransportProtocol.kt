package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Wire-level contract for [BluetoothClassicTransport]/`BluetoothLinkServerService` on
 * both phone-app and watch-app. Must be kept in sync manually between the two
 * independent Gradle projects, same as `datalayer/DataLayerPaths.kt`.
 *
 * Each send opens a fresh RFCOMM socket, writes `path` (UTF), then `length` (unused by
 * the reader - the payload simply runs to end-of-stream when the sender closes the
 * socket), then the payload bytes, then waits for the receiver's one-byte [ACK]/[NACK]
 * reply before closing. One socket carries exactly one payload.
 */
object TransportProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7b1e6b9e-6e0a-4c9b-9e2e-6a2a2f7a9b10")
    const val SERVICE_NAME = "TicWatchConnectorLink"

    /** Receiver's one-byte reply: the payload was fully handled; the sender may drop it. */
    const val ACK: Int = 1

    /**
     * Receiver's one-byte reply: the payload arrived but wasn't durably handled (e.g. the
     * Health Connect write failed); the sender must keep it and retry later.
     */
    const val NACK: Int = 0

    /**
     * Major device classes a bonded watch may advertise, in preference order. The
     * TicWatch reports Uncategorized (CoD 0x001F00) after a factory reset rather than
     * Wearable, so matching Wearable alone misses it.
     */
    private val WATCH_MAJOR_CLASSES = listOf(
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.UNCATEGORIZED,
    )

    /** Finds the bonded device that looks like the watch, by [WATCH_MAJOR_CLASSES] preference. */
    fun findBondedWatch(adapter: BluetoothAdapter): BluetoothDevice? =
        runCatching {
            val bonded = adapter.bondedDevices.orEmpty()
            WATCH_MAJOR_CLASSES.firstNotNullOfOrNull { majorClass ->
                bonded.firstOrNull { it.bluetoothClass?.majorDeviceClass == majorClass }
            }
        }.getOrNull()
}
