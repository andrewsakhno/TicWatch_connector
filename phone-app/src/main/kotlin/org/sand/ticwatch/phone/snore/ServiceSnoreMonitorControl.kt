package org.sand.ticwatch.phone.snore

import android.content.Context
import android.util.Log
import org.sand.ticwatch.phone.transport.BluetoothLinkServerService

/**
 * [SnoreMonitorControl] that hands the request to [BluetoothLinkServerService], whichever listener received the
 * watch's message: only that service holds the microphone foreground type (granted when the app started it from
 * the foreground). A plain startService is allowed because the service already runs in the foreground.
 */
class ServiceSnoreMonitorControl(private val context: Context) : SnoreMonitorControl {

    override fun onSleepStarted() = send(BluetoothLinkServerService.ACTION_START_SNORE_MONITOR)

    override fun onSleepEnded() = send(BluetoothLinkServerService.ACTION_STOP_SNORE_MONITOR)

    private fun send(action: String) {
        runCatching { context.startService(BluetoothLinkServerService.intent(context, action)) }
            .onFailure { Log.w(TAG, "Couldn't reach the link service for $action", it) }
    }

    private companion object {
        const val TAG = "SnoreMonitorControl"
    }
}
