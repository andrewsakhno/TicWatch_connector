package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException

/**
 * [DeviceLinkTransport] that talks directly to the bonded watch over classic Bluetooth
 * RFCOMM, bypassing Google Play Services entirely. Used as the fallback behind
 * [FallbackDeviceLinkTransport] when the Wear Data Layer can't reach the watch (e.g. it
 * was never paired through the official Wear OS by Google flow).
 */
class BluetoothClassicTransport(private val context: Context) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    ?: error("Bluetooth not supported on this device")
                val watch = TransportProtocol.findBondedWatch(adapter) ?: error("No bonded watch found")

                var attempt = 1
                while (true) {
                    try {
                        watch.createRfcommSocketToServiceRecord(TransportProtocol.SERVICE_UUID).use { socket ->
                            socket.connect()
                            val out = DataOutputStream(socket.outputStream)
                            out.writeUTF(path)
                            out.writeLong(length)
                            source.open().use { it.copyTo(out) }
                            out.flush()
                            // Wait for the receiver's one-byte ack before closing - otherwise
                            // the socket can tear down before the payload is fully delivered.
                            socket.inputStream.read()
                        }
                        break
                    } catch (e: IOException) {
                        // RFCOMM connects/acks on this link are observably flaky (a fresh
                        // socket per send, racing against sniff-mode power transitions) -
                        // PayloadSource exists precisely so a failed attempt can be redone
                        // without re-buffering the payload. Retry a few times before giving
                        // up instead of failing the whole install on one transient hiccup.
                        if (attempt >= MAX_ATTEMPTS) throw e
                        attempt++
                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L
    }
}
