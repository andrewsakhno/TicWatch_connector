package org.sand.ticwatch.phone.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.MainActivity
import org.sand.ticwatch.phone.R

/**
 * Hosts [LocalHttpServer] for as long as the MCP agent switch is on; foreground (plus
 * wake/Wi-Fi locks) so Doze and screen-off don't make the server unreachable. Turns
 * itself off after [IDLE_TIMEOUT_MS] without an authenticated request.
 */
class McpAgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: LocalHttpServer? = null

    private val appContainer by lazy { AppContainer(applicationContext) }

    @Volatile private var lastActivityMs = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFromNotification()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        if (server == null) scope.launch { startServer() }
        return START_STICKY
    }

    private suspend fun startServer() {
        val settings = appContainer.mcpAgentSettingsStore
        val handler = McpJsonRpcHandler(appContainer.mcpTools)
        val candidate = LocalHttpServer(settings.getPort(), settings.getToken(), handler) {
            lastActivityMs = SystemClock.elapsedRealtime()
        }
        try {
            candidate.start()
            server = candidate
            lastActivityMs = SystemClock.elapsedRealtime()
            mutableState.value = McpAgentState.Running
            Log.i(TAG, "MCP server listening on port ${settings.getPort()} with ${appContainer.mcpTools.size} tool(s)")
            scope.launch { stopWhenIdle(settings) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start MCP server", e)
            settings.setEnabled(false)
            mutableState.value = McpAgentState.Failed(e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply { acquire() }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG).apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private suspend fun stopWhenIdle(settings: McpAgentSettingsStore) {
        while (true) {
            // An in-flight connection counts as activity: restart the countdown from now.
            if ((server?.activeConnections?.get() ?: 0) > 0) lastActivityMs = SystemClock.elapsedRealtime()
            val remaining = IDLE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastActivityMs)
            if (remaining <= 0) break
            delay(minOf(remaining, IDLE_CHECK_MS))
        }
        settings.setEnabled(false)
        mutableState.value = McpAgentState.StoppedIdle
        stopSelf()
    }

    /** The notification's "Turn off" action: persists the switch as off, like the screen's own switch does. */
    private fun stopFromNotification() {
        scope.launch {
            appContainer.mcpAgentSettingsStore.setEnabled(false)
            mutableState.value = McpAgentState.StoppedByUser
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseLocks()
        server?.stop()
        server = null
        scope.cancel()
        if (mutableState.value is McpAgentState.Running) mutableState.value = McpAgentState.Stopped
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, TITLE, NotificationManager.IMPORTANCE_LOW),
        )
        val openScreen = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            MainActivity.mcpAgentIntent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAgent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, McpAgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(TITLE)
            .setContentText(NOTIFICATION_TEXT)
            .setContentIntent(openScreen)
            // Action buttons are tinted with the notification color.
            .setColor(getColor(R.color.mcp_agent_notification_accent))
            .addAction(Notification.Action.Builder(null as Icon?, STOP_ACTION_LABEL, stopAgent).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "McpAgentService"
        private const val CHANNEL_ID = "mcp_agent"
        private const val NOTIFICATION_ID = 45
        private const val TITLE = "MCP agent"
        private const val NOTIFICATION_TEXT = "MCP server is running"
        private const val STOP_ACTION_LABEL = "Turn off"
        private const val LOCK_TAG = "TicWatchConnector:McpAgent"
        private const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L
        private const val IDLE_CHECK_MS = 10_000L
        private const val ACTION_STOP = "org.sand.ticwatch.phone.mcp.STOP"
        private const val OPEN_REQUEST_CODE = 0
        private const val STOP_REQUEST_CODE = 1

        private val mutableState = MutableStateFlow<McpAgentState>(McpAgentState.Stopped)
        val state: StateFlow<McpAgentState> = mutableState

        fun start(context: Context) {
            context.startForegroundService(Intent(context, McpAgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, McpAgentService::class.java))
        }

        /** Restarts the service if the switch is on; a background-start denial is not fatal. */
        suspend fun restoreIfEnabled(context: Context, settings: McpAgentSettingsStore) {
            if (!settings.isEnabled()) return
            runCatching { start(context) }
                .onFailure { Log.w(TAG, "Could not restore MCP agent", it) }
        }
    }
}

sealed interface McpAgentState {
    data object Stopped : McpAgentState
    data object Running : McpAgentState
    data object StoppedIdle : McpAgentState
    data object StoppedByUser : McpAgentState
    data class Failed(val reason: String) : McpAgentState
}
