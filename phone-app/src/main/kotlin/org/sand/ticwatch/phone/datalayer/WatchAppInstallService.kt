package org.sand.ticwatch.phone.datalayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.sync.SyncStatusRepository
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

    private val installMutex = Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val apkPath = intent?.getStringExtra(EXTRA_APK_PATH)
        startForeground(NOTIFICATION_ID, buildNotification())

        if (apkPath == null) {
            Log.w(TAG, "Missing $EXTRA_APK_PATH extra")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val progressUpdates = serviceScope.launch { showApkTransferProgress() }
        serviceScope.launch {
            // Overlapping starts would interleave two request+APK sends over the same link: the
            // watch tracks a single pending install and the RFCOMM connections collide, so
            // both fail. Queue them instead.
            if (installMutex.isLocked) Log.i(TAG, "Another watch-app install is in progress; queued")
            installMutex.withLock {
                AppContainer(applicationContext).watchAppManager
                    .installApp(watchNodeId = "", apk = File(apkPath), packageName = WATCH_APP_PACKAGE_NAME)
                    .onSuccess { Log.i(TAG, "Sent watch-app install request + APK over the bridge") }
                    .onFailure { error -> Log.w(TAG, "Failed to install watch-app over the bridge", error) }
            }
            progressUpdates.cancel()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Mirrors the APK channel transfer into the notification as a progress bar, so a
     * multi-minute upload is visible without opening the app. Updates only when the
     * whole percentage changes: posting a notification per Bluetooth chunk gets
     * rate-limited by the system.
     */
    private suspend fun showApkTransferProgress() {
        val manager = getSystemService(NotificationManager::class.java)
        SyncStatusRepository.transfers
            .map { transfers -> transfers.firstOrNull { it.path == DataLayerPaths.CHANNEL_APK_TRANSFER } }
            .map { transfer -> transfer?.let { ApkProgress(it.bytesTransferred, it.totalBytes, it.fraction?.times(PERCENT)?.toInt()) } }
            .distinctUntilChanged { old, new -> old?.percent == new?.percent && (old == null) == (new == null) }
            .collect { progress -> manager.notify(NOTIFICATION_ID, buildNotification(progress)) }
    }

    private class ApkProgress(val bytesTransferred: Long, val totalBytes: Long?, val percent: Int?)

    private fun buildNotification(progress: ApkProgress? = null): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch app install", NotificationManager.IMPORTANCE_LOW),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Updating watch app")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOnlyAlertOnce(true)
        val percent = progress?.percent
        return when {
            progress == null -> builder.setContentText("Waiting for the watch link").setProgress(0, 0, true)
            percent == null -> builder.setContentText("Sending APK to watch").setProgress(0, 0, true)
            else -> builder
                .setContentText("Sending APK: $percent% (${progress.bytesTransferred / BYTES_PER_MB} / ${(progress.totalBytes ?: 0) / BYTES_PER_MB} MB)")
                .setProgress(PERCENT, percent, false)
        }.build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val EXTRA_APK_PATH = "apkPath"
        private const val NOTIFICATION_ID = 44
        private const val CHANNEL_ID = "watch_app_install"
        private const val TAG = "WatchAppInstallService"
        private const val PERCENT = 100
        private const val BYTES_PER_MB = 1024 * 1024
    }
}
