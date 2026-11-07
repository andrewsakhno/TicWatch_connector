package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Runs [block] on this socket and closes the socket if it hasn't finished within [timeout]. `BluetoothSocket` has no
 * read timeout of its own: a peer that never answers otherwise blocks the caller indefinitely. Closing makes the
 * blocked `read()` throw, so the caller fails and moves on.
 */
internal suspend fun <T> BluetoothSocket.withDeadline(timeout: Duration, what: String, block: (BluetoothSocket) -> T): T =
    coroutineScope {
        val watchdog = launch {
            delay(timeout.toMillis())
            Log.w(SOCKET_DEADLINE_TAG, "$what took longer than $timeout, closing the socket")
            runCatching { close() }
        }
        try {
            block(this@withDeadline)
        } finally {
            watchdog.cancel()
        }
    }

private const val SOCKET_DEADLINE_TAG = "SocketDeadline"
