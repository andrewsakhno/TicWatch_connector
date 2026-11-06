package org.sand.ticwatch.phone.deviation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.MainActivity
import org.sand.ticwatch.phone.R
import java.time.ZoneId
import kotlin.math.roundToInt

/** Posts a [DeviationQuestion]; a tap opens the Deviations screen, and a single episode also gets one-tap answers. */
class SystemDeviationQuestionNotifier(context: Context, private val zone: ZoneId) : DeviationQuestionNotifier {

    private val context = context.applicationContext
    private val manager = this.context.getSystemService(NotificationManager::class.java)

    override fun ask(question: DeviationQuestion): Boolean {
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled - deviation question not shown")
            return false
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.deviation_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val only = question.waiting.singleOrNull()
        val text = if (only != null) describe(only) else context.getString(R.string.deviation_notif_many, question.waiting.size)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(context.getString(R.string.deviation_notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openScreen())
            .setAutoCancel(true)
        if (only != null) question.quickReasons.forEach { builder.addAction(answerAction(only, it)) }
        manager.notify(NOTIFICATION_ID, builder.build())
        return true
    }

    override fun dismiss() = manager.cancel(NOTIFICATION_ID)

    private fun describe(episode: DeviationEpisode): String {
        val summary = context.getString(episode.direction.label, episode.peakBpm.roundToInt())
        return "$summary, ${DeviationPeriodFormat(zone).period(episode)}"
    }

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_REQUEST_CODE,
        MainActivity.deviationsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun answerAction(episode: DeviationEpisode, reason: DeviationReason): Notification.Action {
        val intent = Intent(context, DeviationAnswerReceiver::class.java)
            .putExtra(DeviationAnswerReceiver.EXTRA_EPISODE_ID, episode.id)
            .putExtra(DeviationAnswerReceiver.EXTRA_REASON_CODE, reason.code)
        val answer = PendingIntent.getBroadcast(
            context, ANSWER_REQUEST_CODE_BASE + reason.code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(context, android.R.drawable.ic_menu_edit)
        return Notification.Action.Builder(icon, context.getString(reason.label), answer).build()
    }

    private companion object {
        const val TAG = "DeviationQuestion"
        const val CHANNEL_ID = "channel_deviation_questions"
        const val NOTIFICATION_ID = 4_201
        const val OPEN_REQUEST_CODE = 4_202
        const val ANSWER_REQUEST_CODE_BASE = 4_300
    }
}

/** Stores the answer of a notification's button; the process may have been started just for this. */
class DeviationAnswerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_EPISODE_ID, NO_VALUE.toLong())
        val reason = DeviationReason.of(intent.getIntExtra(EXTRA_REASON_CODE, NO_VALUE))
        if (id == NO_VALUE.toLong() || reason == null) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppContainer(appContext).deviationAnswers.answer(id, setOf(reason))
            } catch (e: Exception) {
                Log.w(TAG, "Storing the answer failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DeviationAnswer"
        private const val NO_VALUE = -1
        const val EXTRA_EPISODE_ID = "org.sand.ticwatch.phone.extra.DEVIATION_EPISODE_ID"
        const val EXTRA_REASON_CODE = "org.sand.ticwatch.phone.extra.DEVIATION_REASON_CODE"
    }
}
