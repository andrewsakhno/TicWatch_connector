package org.sand.ticwatch.watch.sensors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Process-wide hand-off from a fired alarm broadcast back to the coroutine waiting on it. */
object WakeAlarms {
    private val nextId = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, () -> Unit>()

    fun register(onFire: () -> Unit): Int = nextId.incrementAndGet().also { pending[it] = onFire }

    fun unregister(id: Int) {
        pending.remove(id)
    }

    /** Runs and forgets the waiter; an alarm outliving its process (restarted since) finds none and does nothing. */
    fun fire(id: Int) {
        pending.remove(id)?.invoke()
    }
}

/** Receives [AlarmWindowScheduler]'s allow-while-idle alarms; not exported, only its own PendingIntents reach it. */
class WakeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ALARM_ID, NO_ID)
        if (id != NO_ID) WakeAlarms.fire(id)
    }

    companion object {
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val NO_ID = -1
        private const val URI_SCHEME = "ticwatch-alarm"

        /** One distinct PendingIntent per wait (the data URI keeps them apart), so cancelling one leaves the others. */
        fun pendingIntent(context: Context, tag: String, id: Int): PendingIntent {
            val intent = Intent(context, WakeAlarmReceiver::class.java)
                .setData(Uri.fromParts(URI_SCHEME, tag, id.toString()))
                .putExtra(EXTRA_ALARM_ID, id)
            return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        }
    }
}
