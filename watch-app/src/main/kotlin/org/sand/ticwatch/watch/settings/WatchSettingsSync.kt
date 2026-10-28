package org.sand.ticwatch.watch.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.phonedata.PhonePayloadSink
import org.sand.ticwatch.watch.transport.DeviceLinkTransport
import org.sand.ticwatch.watch.transport.TransportFactory
import org.sand.ticwatch.watch.transport.sendMessage
import java.time.Clock

/**
 * Keeps [WatchSettings] in step with the phone, newest change wins: a change made here is stored and sent; a copy
 * from the phone replaces an older local one, and an older phone copy is answered with the local one (the phone
 * re-sends its copy after every sensor push, so a change made while the link was down still reaches it).
 */
class WatchSettingsSync(
    private val store: WatchSettingsStore,
    private val transport: DeviceLinkTransport,
    private val scope: CoroutineScope,
    private val clock: Clock,
) : PhonePayloadSink {

    fun change(transform: (WatchSettings) -> WatchSettings) {
        val updated = transform(store.settings.value).copy(changedAtEpochMs = clock.millis())
        store.save(updated)
        send(updated)
    }

    override fun accept(payload: String) {
        val remote = runCatching { WatchSettingsPayload.decode(payload) }
            .onFailure { Log.w(TAG, "Malformed watch settings from the phone", it) }
            .getOrNull() ?: return
        val local = store.settings.value
        when {
            remote.changedAtEpochMs > local.changedAtEpochMs -> store.save(remote)
            local.changedAtEpochMs > remote.changedAtEpochMs -> send(local)
        }
    }

    private fun send(settings: WatchSettings) {
        scope.launch {
            transport.sendMessage(DataLayerPaths.MESSAGE_WATCH_SETTINGS, WatchSettingsPayload.encode(settings).toByteArray(Charsets.UTF_8))
                .onFailure { Log.w(TAG, "Sending watch settings to the phone failed; resent on its next copy", it) }
        }
    }

    companion object {
        private const val TAG = "WatchSettingsSync"

        fun create(context: Context, scope: CoroutineScope): WatchSettingsSync = WatchSettingsSync(
            SharedPreferencesWatchSettingsStore.get(context), TransportFactory.create(context), scope, Clock.systemUTC(),
        )
    }
}
