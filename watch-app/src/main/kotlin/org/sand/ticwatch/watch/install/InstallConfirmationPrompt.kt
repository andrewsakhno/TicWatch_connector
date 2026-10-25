package org.sand.ticwatch.watch.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Surfaces PackageInstaller's confirmation activity. A direct `startActivity` from a
 * background receiver is silently dropped by Android's background-activity-launch limits
 * (no dialog, no error), so the same intent is also posted as a high-priority notification
 * the user can tap - that path is exempt from those limits.
 */
class InstallConfirmationPrompt(private val context: Context) {

    fun show(sessionId: Int, confirmationIntent: Intent) {
        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirmationIntent) }
            .onFailure { Log.w(TAG, "Direct launch of install confirmation failed", it) }
        // SYSTEM_ALERT_WINDOW exempts the app from background-launch limits, so the direct
        // launch above already shows the dialog and the notification would just be clutter.
        if (Settings.canDrawOverlays(context)) return
        runCatching { postNotification(sessionId, confirmationIntent) }
            .onFailure { Log.w(TAG, "Failed to post install-confirmation notification", it) }
    }

    private fun postNotification(sessionId: Int, confirmationIntent: Intent) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App update confirmation", NotificationManager.IMPORTANCE_HIGH),
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId,
            confirmationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("TicWatch Connector update")
            .setContentText("Tap to confirm installing the new version")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "install_confirmation"
        private const val NOTIFICATION_ID = 45
        private const val TAG = "InstallConfirmation"
    }
}
