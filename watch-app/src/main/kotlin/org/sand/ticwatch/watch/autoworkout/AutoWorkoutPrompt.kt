package org.sand.ticwatch.watch.autoworkout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/** What the wearer sees of automatic workouts. */
interface AutoWorkoutPrompt {
    /** "Walking detected - start a workout?" with a start action. */
    fun askToStart()

    /** Ongoing "walk recording" with an end action; replaces the question. */
    fun showRunning()

    fun clear()
}

/**
 * [AutoWorkoutPrompt] as notifications whose actions start [serviceClass] with [startAction] /
 * [endAction]: the service is already running in the foreground, so delivering them needs no
 * background-start exemption.
 */
class NotificationAutoWorkoutPrompt(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val startAction: String,
    private val endAction: String,
) : AutoWorkoutPrompt {

    private val manager by lazy {
        context.getSystemService(NotificationManager::class.java).also {
            it.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun askToStart() {
        manager.notify(
            NOTIFICATION_ID,
            builder("Walking detected", "Record it as a workout?")
                .addAction(action(android.R.drawable.ic_media_play, "Start", startAction, START_REQUEST_CODE))
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun showRunning() {
        manager.notify(
            NOTIFICATION_ID,
            builder("Walk workout", "Recording - ends when you stop walking")
                .addAction(action(android.R.drawable.ic_media_pause, "End", endAction, END_REQUEST_CODE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    override fun clear() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun builder(title: String, text: String): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setCategory(Notification.CATEGORY_WORKOUT)

    private fun action(icon: Int, title: String, intentAction: String, requestCode: Int): Notification.Action {
        val intent = Intent(context, serviceClass).setAction(intentAction)
        val pending = PendingIntent.getService(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), title, pending).build()
    }

    private companion object {
        const val CHANNEL_ID = "auto_workout"
        const val CHANNEL_NAME = "Automatic walk workouts"
        const val NOTIFICATION_ID = 44
        const val START_REQUEST_CODE = 1
        const val END_REQUEST_CODE = 2
    }
}
