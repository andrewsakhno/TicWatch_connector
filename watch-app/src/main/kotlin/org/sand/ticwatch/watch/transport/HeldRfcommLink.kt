package org.sand.ticwatch.watch.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Keeps the sender's RFCOMM socket open across frames between [hold] and [release]. */
interface LinkHold {
    fun hold()

    /** Ends the hold and closes the socket, interrupting a send still blocked on it. */
    fun release()
}

/**
 * One RFCOMM socket reused for every frame while held, instead of a fresh connection per send (live sync sends every
 * few seconds). A socket that died while idle is replaced once within the same send. Mirrored by phone-app.
 */
class HeldRfcommLink : LinkHold {
    private val sending = Mutex()
    @Volatile private var held = false
    @Volatile private var socket: BluetoothSocket? = null

    override fun hold() {
        held = true
    }

    override fun release() {
        held = false
        closeSocket()
    }

    /** Sends one frame over the held socket and returns true; false when not held (nothing was sent). */
    suspend fun trySend(connect: () -> BluetoothSocket, writeFrame: (BluetoothSocket) -> Unit): Boolean = sending.withLock {
        if (!held) return false
        socket?.let { existing ->
            try {
                writeFrame(existing)
                return true
            } catch (e: IOException) {
                closeSocket()
                if (!held) throw e
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
        if (!held) closeSocket()
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
