package org.sand.ticwatch.phone.watchsettings

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.WatchStatePublisher
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.sendMessage
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Receives the watch's copy of [WatchSettings]. */
fun interface WatchSettingsReceiver {
    fun onWatchSettings(payload: ByteArray)
}

/**
 * Keeps [WatchSettings] in step with the watch, newest change wins: a change made here is stored and sent; a copy
 * from the watch replaces an older local one, and the local copy is re-sent after every sensor push until the
 * watch has it ([publish], deduplicated), so a change made while the watch was out of reach still lands.
 */
class WatchSettingsSync(
    private val store: WatchSettingsStore,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : WatchStatePublisher, WatchSettingsReceiver {

    private val lastSent = AtomicReference<String?>()

    fun change(transform: (WatchSettings) -> WatchSettings) {
        store.save(transform(store.settings.value).copy(changedAtEpochMs = clock.millis()))
        publish()
    }

    override fun onWatchSettings(payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8)
        val remote = runCatching { WatchSettingsPayload.decode(text) }
            .onFailure { Log.w(TAG, "Malformed watch settings from the watch: $text", it) }
            .getOrNull() ?: return
        if (remote.changedAtEpochMs <= store.settings.value.changedAtEpochMs) return
        store.save(remote)
        // The watch already holds this copy: no need to echo it back.
        lastSent.set(WatchSettingsPayload.encode(remote))
    }

    override fun publish() {
        scope.launch {
            val payload = WatchSettingsPayload.encode(store.settings.value)
            if (payload == lastSent.get()) return@launch
            transport.sendMessage(DataLayerPaths.MESSAGE_WATCH_SETTINGS, payload.toByteArray(Charsets.UTF_8))
                .onSuccess { lastSent.set(payload) }
                .onFailure { Log.w(TAG, "Sending watch settings to the watch failed", it) }
        }
    }

    private companion object {
        const val TAG = "WatchSettingsSync"
    }
}
