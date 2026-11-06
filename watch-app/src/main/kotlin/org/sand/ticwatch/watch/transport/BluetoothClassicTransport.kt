package org.sand.ticwatch.watch.transport

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException
import java.time.Duration

/**
 * [DeviceLinkTransport] that talks directly to the bonded phone over classic Bluetooth
 * RFCOMM, bypassing Google Play Services entirely. Used as the fallback behind
 * [FallbackDeviceLinkTransport] when the Wear Data Layer can't reach the phone (e.g. the
 * watch was never paired through the official Wear OS by Google flow). Reuses [heldLink]'s socket while it is held.
 */
class BluetoothClassicTransport(
    private val context: Context,
    private val heldLink: HeldRfcommLink = HeldRfcommLink(),
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: error("Bluetooth not supported on this device")
                val phone = TransportProtocol.findBondedPhone(adapter) ?: error("No bonded phone found")

                val sentHeld = heldLink.trySend(phone::connectLinkSocket) { socket -> writeFrame(socket, path, length, source) }
                if (!sentHeld) phone.connectLinkSocket().use { socket -> writeFrame(socket, path, length, source) }
            }
        }

    private suspend fun writeFrame(socket: BluetoothSocket, path: String, length: Long, source: PayloadSource) =
        socket.withDeadline(FRAME_DEADLINE, "Sending $path") { writeFrameBlocking(it, path, length, source) }

    private fun writeFrameBlocking(socket: BluetoothSocket, path: String, length: Long, source: PayloadSource) {
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

    private companion object {
        /**
         * Covers the transfer and the phone's handling before its ACK (Health Connect writes, derivers); shorter than the
         * sync flush's 2 min wake lock, so it fires before the watch can suspend with the flush still stuck.
         */
        val FRAME_DEADLINE: Duration = Duration.ofSeconds(90)
    }
}
