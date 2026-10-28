package org.sand.ticwatch.watch.datalayer

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.sendMessage

/**
 * Sends an on/off state to the phone on [path], retrying, where the latest state wins: a send still retrying
 * when the state flips again is abandoned, so a late "on" can never overtake an "off".
 */
class LatestMessageSender(
    private val transport: DeviceLinkTransport,
    scope: CoroutineScope,
    private val path: String,
    private val encode: (Boolean) -> ByteArray,
) {

    private val state = MutableStateFlow<Boolean?>(null)

    init {
        scope.launch {
            state.filterNotNull().collectLatest { active -> sendWithRetry(active) }
        }
    }

    /** The last state handed to [update], null before the first. */
    val current: Boolean? get() = state.value

    fun update(active: Boolean) {
        state.value = active
    }

    private suspend fun sendWithRetry(active: Boolean) {
        val payload = encode(active)
        repeat(MAX_ATTEMPTS) { attempt ->
            if (transport.sendMessage(path, payload).isSuccess) {
                Log.i(TAG, "Sent $path active=$active")
                return
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        Log.w(TAG, "Gave up sending $path active=$active after $MAX_ATTEMPTS attempts")
    }

    private companion object {
        const val TAG = "LatestMessageSender"
        const val MAX_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 10_000L
    }
}
