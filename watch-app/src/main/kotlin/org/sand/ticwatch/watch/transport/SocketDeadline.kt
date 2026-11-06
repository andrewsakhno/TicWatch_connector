package org.sand.ticwatch.watch.transport

import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Runs [block] on this socket and closes the socket if it hasn't finished within [timeout]. `BluetoothSocket` has no
 * read/connect timeout of its own: a link that stalls without dropping (the phone never answers, the stack never
 * completes the connect) otherwise blocks the caller - and with it the sync sender's flush lock - until something else
 * happens to wake the radio. Closing makes the blocked `connect()`/`read()` throw, so the caller fails and retries.
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
