package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
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

                val upload = if (length > TransportProtocol.RESUMABLE_THRESHOLD) {
                    ResumableUpload(path, length, source, source.contentHash())
                } else {
                    null
                }
                val preemption = currentLinkPreemption()
                var resumable = upload != null
                var attempt = 1
                while (true) {
                    if (preemption?.isRequested == true) preemption.yieldLink()
                    val offsetBefore = upload?.confirmedOffset
                    try {
                        val outcome = watch.createRfcommSocketToServiceRecord(TransportProtocol.SERVICE_UUID).use { socket ->
                            socket.connect()
                            if (upload != null && resumable) {
                                upload.runOn(socket) { preemption?.isRequested == true }
                            } else {
                                sendOneShotFrame(socket, path, length, source)
                                ResumableUpload.Outcome.COMPLETED
                            }
                        }
                        when (outcome) {
                            ResumableUpload.Outcome.COMPLETED -> break
                            ResumableUpload.Outcome.PEER_IS_LEGACY -> {
                                Log.i(TAG, "Watch predates resumable transfers, resending $path as one frame")
                                resumable = false
                            }
                            // Socket is closed here; the loop head yields, then reconnects and resumes.
                            ResumableUpload.Outcome.PAUSED -> {
                                Log.i(TAG, "Pausing $path at ${upload?.confirmedOffset}/$length for interactive sends")
                                attempt = 1
                            }
                        }
                    } catch (e: IOException) {
                        // A resumable attempt that got further than the previous one isn't a
                        // failure streak: the link is alive, just flaky - keep resuming.
                        if (upload != null && e !is ContentHashMismatchException && upload.confirmedOffset != offsetBefore) {
                            attempt = 1
                        }
                        // RFCOMM connects/acks on this link are observably flaky (a fresh
                        // socket per send, racing against sniff-mode power transitions) -
                        // PayloadSource exists precisely so a failed attempt can be redone
                        // without re-buffering the payload. Retry a few times before giving
                        // up instead of failing the whole install on one transient hiccup.
                        // Back off exponentially: a watch waking from sniff mode or restarting
                        // its listener needs seconds, not the half-second a fixed delay gave.
                        Log.w(TAG, "RFCOMM attempt $attempt/$MAX_ATTEMPTS for $path failed: $e")
                        if (attempt >= MAX_ATTEMPTS) throw e
                        delay(INITIAL_RETRY_DELAY_MS shl (attempt - 1))
                        attempt++
                    }
                }
            }
        }

    private fun sendOneShotFrame(socket: BluetoothSocket, path: String, length: Long, source: PayloadSource) {
        val out = DataOutputStream(socket.outputStream)
        out.writeUTF(path)
        out.writeLong(length)
        source.open().use { it.copyTo(out) }
        out.flush()
        // Wait for the receiver's one-byte ack before closing - otherwise
        // the socket can tear down before the payload is fully delivered.
        socket.inputStream.read()
    }

    companion object {
        private const val TAG = "BluetoothClassicTransport"
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
    }
}
