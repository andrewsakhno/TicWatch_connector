package org.sand.ticwatch.watch.sensors

import android.app.AlarmManager
import android.os.Handler
import android.os.Looper
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
 * start. The alarm counts suspend time and wakes the device. An inexact [AlarmManager.setWindow]
 * needs no exact-alarm permission; Doze may defer it to a maintenance window, which is fine
 * for a 30 min cadence. On firing it takes a short wake lock so the device can't go back to
 * sleep before the window acquires its own.
 */
class AlarmWindowScheduler(
    private val alarmManager: AlarmManager,
    private val powerManager: PowerManager,
    private val alarmTag: String,
) : WindowScheduler {

    private val handler = Handler(Looper.getMainLooper())

    override suspend fun awaitNext(interval: Duration) = suspendCancellableCoroutine { continuation ->
        val listener = AlarmManager.OnAlarmListener {
            powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).acquire(HANDOFF_WAKE_LOCK.toMillis())
            continuation.resume(Unit)
        }
        alarmManager.setWindow(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + interval.toMillis(),
            ALARM_WINDOW.toMillis(),
            alarmTag,
            listener,
            handler,
        )
        continuation.invokeOnCancellation { alarmManager.cancel(listener) }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "TicWatchConnector:AlarmHandoff"
        val ALARM_WINDOW: Duration = Duration.ofMinutes(1)
        val HANDOFF_WAKE_LOCK: Duration = Duration.ofSeconds(15)
    }
}
