package org.sand.ticwatch.phone.transport

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.mcp.McpAgentService

/**
 * Restarts the long-running services after a phone reboot or an app update. Both kill
 * them and neither restores a START_STICKY service:
 *
 * - [BluetoothLinkServerService]: otherwise watch pushes went unanswered (and piled up
 *   in the watch's buffer) until someone opened the app again. Started only once
 *   BLUETOOTH_CONNECT is granted: a connectedDevice foreground service without it
 *   throws SecurityException (the first-run grant happens in MainActivity).
 * - [McpAgentService]: only if the user left the MCP agent switch on.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in TRIGGER_ACTIONS) return
        if (isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
            ContextCompat.startForegroundService(context, Intent(context, BluetoothLinkServerService::class.java))
        }
        restoreMcpAgent(context.applicationContext)
    }

    private fun restoreMcpAgent(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                McpAgentService.restoreIfEnabled(context, AppContainer(context).mcpAgentSettingsStore)
            } finally {
                pending.finish()
            }
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val TRIGGER_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
