package org.sand.ticwatch.phone.spaceweather

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.sand.ticwatch.phone.MainActivity

/** Posts a [GeomagneticAlert] on [CHANNEL_ID]; tapping it opens the app with the cardio advice sheet. */
class SystemGeomagneticAlertNotifier(context: Context) : GeomagneticAlertNotifier {

    private val context = context.applicationContext

    override fun notify(alert: GeomagneticAlert) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled - geomagnetic alert ${alert.level} not shown")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
        val openAdvice = PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            MainActivity.cardioAdviceIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "⚠️ Geomagnetic storm forecast (Kp = ${"%.1f".format(alert.maxKp)}, ${alert.level.label})"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(TEXT)
            .setStyle(Notification.BigTextStyle().bigText(TEXT))
            .setContentIntent(openAdvice)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "GeomagneticAlert"
        const val CHANNEL_ID = "channel_space_weather_alerts"
        const val CHANNEL_NAME = "Geomagnetic storm alerts"
        const val NOTIFICATION_ID = 4_101
        const val OPEN_REQUEST_CODE = 4_102
        const val TEXT = "A geomagnetic disturbance is expected. Check your blood pressure with the cuff and reduce peak exertion."
    }
}
