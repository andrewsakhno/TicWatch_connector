package org.sand.ticwatch.watch.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException

/**
 * [DeviceLinkTransport] that talks directly to the bonded phone over classic Bluetooth
 * RFCOMM, bypassing Google Play Services entirely. Used as the fallback behind
 * [FallbackDeviceLinkTransport] when the Wear Data Layer can't reach the phone (e.g. the
 * watch was never paired through the official Wear OS by Google flow).
 */
class BluetoothClassicTransport(private val context: Context) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: error("Bluetooth not supported on this device")
                val phone = TransportProtocol.findBondedPhone(adapter) ?: error("No bonded phone found")

                phone.createRfcommSocketToServiceRecord(TransportProtocol.SERVICE_UUID).use { socket ->
                    socket.connect()
                    val out = DataOutputStream(socket.outputStream)
                    out.writeUTF(path)
                    out.writeLong(length)
                    source.open().use { it.copyTo(out) }
                    out.flush()
                    // Wait for the receiver's one-byte reply before closing - otherwise
                    // the socket can tear down before the payload is fully delivered. Anything
                    // but ACK (NACK, or -1 on a closed socket) fails the send so the caller keeps
                    // the payload buffered for a retry instead of dropping it.
                    val reply = socket.inputStream.read()
                    if (reply != TransportProtocol.ACK) throw IOException("Phone did not accept $path (reply=$reply)")
                }
            }
        }
}
