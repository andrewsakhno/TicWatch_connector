package org.sand.ticwatch.watch.sensors

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Duration
import kotlin.coroutines.resume

/** Suspends until the next periodic run (measurement window, sync flush) is due. */
fun interface WindowScheduler {
    suspend fun awaitNext(interval: Duration)
}

/**
 * [WindowScheduler] on an `ELAPSED_REALTIME_WAKEUP` alarm. A coroutine `delay` runs on the
 * monotonic clock, which stops while the application processor is suspended - so on a
 * screen-off watch a 30 min delay effectively never elapsed and windows only ran at service
 * start. The alarm counts suspend time and wakes the device.
 *
 * It is an allow-while-idle alarm (exact through `USE_EXACT_ALARM` when granted): a plain `setWindow` alarm is
 * deferred by Doze to its maintenance windows, which stopped measurements and sync for 4-5 h on still nights and
 * left a dropped phone link down until the app was opened. Allow-while-idle alarms only exist with a
 * [PendingIntent], so [WakeAlarmReceiver] hands the firing back through [WakeAlarms]. On firing it takes a short
 * wake lock so the device can't go back to sleep before the window acquires its own.
 */
class AlarmWindowScheduler(
    private val context: Context,
    private val alarmManager: AlarmManager,
    private val powerManager: PowerManager,
    private val alarmTag: String,
) : WindowScheduler {

    override suspend fun awaitNext(interval: Duration) = suspendCancellableCoroutine { continuation ->
        val id = WakeAlarms.register {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).acquire(HANDOFF_WAKE_LOCK.toMillis())
            continuation.resume(Unit)
        }
        val operation = WakeAlarmReceiver.pendingIntent(context, alarmTag, id)
        val triggerAt = SystemClock.elapsedRealtime() + interval.toMillis()
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, operation)
        }
        continuation.invokeOnCancellation {
            alarmManager.cancel(operation)
            WakeAlarms.unregister(id)
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "TicWatchConnector:AlarmHandoff"

        /** Held whole by every alarm, even one that finds nothing to do; covers the sleep check's 3 s flush settle. */
        val HANDOFF_WAKE_LOCK: Duration = Duration.ofSeconds(5)
    }
}
