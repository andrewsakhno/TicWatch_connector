package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the sender's RFCOMM socket open across frames between [hold] and [release]; every hold needs its release. */
interface LinkHold {
    fun hold()

    /** Ends this hold; the last one closes the socket, interrupting a send still blocked on it. */
    fun release()
}

/**
 * One RFCOMM socket reused for every frame while held, instead of a fresh connection per send (live sync sends every
 * few seconds; replies to a watch flush go out together). Counted, since those two overlap. A socket that died while
 * idle is replaced once within the same send. Mirrored by watch-app, whose single holder needs no count.
 */
class HeldRfcommLink : LinkHold {
    private val sending = Mutex()
    private val holds = AtomicInteger()
    @Volatile private var socket: BluetoothSocket? = null

    override fun hold() {
        holds.incrementAndGet()
    }

    override fun release() {
        if (holds.decrementAndGet() == 0) closeSocket()
    }

    /** Sends one frame over the held socket and returns true; false when not held (nothing was sent). */
    suspend fun trySend(connect: () -> BluetoothSocket, writeFrame: (BluetoothSocket) -> Unit): Boolean = sending.withLock {
        if (holds.get() == 0) return false
        socket?.let { existing ->
            try {
                writeFrame(existing)
                return true
            } catch (e: IOException) {
                closeSocket()
                if (holds.get() == 0) throw e
                Log.i(TAG, "Held socket died, reconnecting: $e")
            }
        }
        val fresh = connect()
        socket = fresh
        try {
            writeFrame(fresh)
        } catch (e: IOException) {
            closeSocket()
            throw e
        }
        // Released while connecting: nobody is left to close it.
        if (holds.get() == 0) closeSocket()
        true
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val TAG = "HeldRfcommLink"
    }
}

/** A connected socket to the link service of this device; closed again when the connect fails. */
internal fun BluetoothDevice.connectLinkSocket(): BluetoothSocket =
    createRfcommSocketToServiceRecord(TransportProtocol.SERVICE_UUID).apply {
        try {
            connect()
        } catch (e: IOException) {
            runCatching { close() }
            throw e
        }
    }
