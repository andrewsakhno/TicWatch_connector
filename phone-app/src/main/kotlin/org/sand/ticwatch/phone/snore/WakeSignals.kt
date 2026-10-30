package org.sand.ticwatch.phone.snore

import android.app.AlarmManager
import org.sand.ticwatch.phone.health.SampleSource
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorType
import java.time.Duration
import java.time.Instant

/** One sign that the user is up, checked periodically by [PhoneSnoreMonitor] to end a night the watch never closed. */
interface WakeSignal {
    val name: String

    /** True once the user is evidently awake after [nightStart]. */
    suspend fun isAwake(nightStart: Instant, now: Instant): Boolean
}

/**
 * The alarm clock rang: remembers the next alarm seen at the previous check and reports it once its time has passed.
 * [AlarmManager.getNextAlarmClock] only lists future alarms, so a fired one shows as the remembered time going by;
 * an alarm cancelled before it rang is simply replaced by the next one at the following check.
 */
class AlarmClockWakeSignal(private val alarmManager: AlarmManager) : WakeSignal {

    override val name = "alarm clock"

    private var night: Instant? = null
    private var expectedAlarm: Instant? = null

    // Only one night's watcher coroutine calls this, sequentially.
    override suspend fun isAwake(nightStart: Instant, now: Instant): Boolean {
        if (night != nightStart) {
            night = nightStart
            expectedAlarm = null
        }
        val rang = expectedAlarm?.let { !it.isAfter(now) } == true
        expectedAlarm = alarmManager.nextAlarmClock?.triggerTime?.let(Instant::ofEpochMilli)
        return rang
    }
}

/**
 * The watch counts walking: at least [minSteps] watch steps recorded in the last [lookback] since the night began.
 * The lookback covers the watch's batched pushes (flushed every 30 min), so late batches still count.
 */
class WatchStepsWakeSignal(
    private val samples: SampleWindowReader,
    private val minSteps: Double = DEFAULT_MIN_STEPS,
    private val lookback: Duration = DEFAULT_LOOKBACK,
) : WakeSignal {

    override val name = "watch activity"

    override suspend fun isAwake(nightStart: Instant, now: Instant): Boolean {
        val from = maxOf(nightStart, now.minus(lookback))
        if (!from.isBefore(now)) return false
        val steps = samples.samples(STEPS, from, now, emptyList())
            .filter { it.source == SampleSource.WATCH }
            .sumOf { it.value }
        return steps >= minSteps
    }

    private companion object {
        val STEPS = setOf(SensorType.STEPS)
        const val DEFAULT_MIN_STEPS = 150.0
        val DEFAULT_LOOKBACK: Duration = Duration.ofMinutes(35)
    }
}

/** The app was opened on the phone after the night began. */
class AppOpenedWakeSignal(private val activity: AppActivityLog) : WakeSignal {

    override val name = "app activity"

    override suspend fun isAwake(nightStart: Instant, now: Instant): Boolean =
        activity.lastActiveAt?.isAfter(nightStart) == true
}

/** When the user last had the app in the foreground. */
interface AppActivityLog {
    val lastActiveAt: Instant?
    fun markActive(at: Instant)
}

/** Process-wide [AppActivityLog]: the activity and the link service hosting the monitor share one process. */
object ProcessAppActivity : AppActivityLog {
    @Volatile
    override var lastActiveAt: Instant? = null
        private set

    override fun markActive(at: Instant) {
        lastActiveAt = at
    }
}
