package org.sand.ticwatch.phone.datalayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import java.io.File

/**
 * Runs [AppContainer.watchAppManager]'s `installApp` as a foreground service instead of
 * a bare coroutine. A multi-minute Bluetooth Classic APK transfer started from
 * [DebugBridgeReceiver] (no visible Activity keeping the process warm) otherwise runs in
 * an empty/cached process, which Android's cached-app freezer suspends entirely shortly
 * after the triggering broadcast returns - the transfer stalls indefinitely with no
 * crash and no log output. `startForeground` keeps the process out of that cached state
 * for as long as the transfer takes.
 */
class WatchAppInstallService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apkPath = intent?.getStringExtra(EXTRA_APK_PATH)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (apkPath == null) {
            Log.w(TAG, "Missing $EXTRA_APK_PATH extra")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        CoroutineScope(Dispatchers.IO).launch {
            AppContainer(applicationContext).watchAppManager
                .installApp(watchNodeId = "", apk = File(apkPath), packageName = WATCH_APP_PACKAGE_NAME)
                .onSuccess { Log.i(TAG, "Sent watch-app install request + APK over the bridge") }
                .onFailure { error -> Log.w(TAG, "Failed to install watch-app over the bridge", error) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch app install", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TicWatch Connector")
            .setContentText("Installing watch-app build over Bluetooth")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val EXTRA_APK_PATH = "apkPath"
        private const val NOTIFICATION_ID = 44
        private const val CHANNEL_ID = "watch_app_install"
        private const val TAG = "WatchAppInstallService"
    }
}
