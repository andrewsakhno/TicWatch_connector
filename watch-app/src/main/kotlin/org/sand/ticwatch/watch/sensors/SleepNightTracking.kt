package org.sand.ticwatch.watch.sensors

import android.content.Context
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The stage of the open sleep session right now, since when, and when the last wake inside it ended. */
data class CurrentSleepStage(val stage: SleepStage, val since: Instant, val lastWakeEndedAt: Instant?)

/** Where the open sleep session stands; null outside one or when the watch has no stage sensor. */
fun interface SleepStageState {
    fun current(): CurrentSleepStage?
}

/** The wake-up times of recent nights, oldest first. */
interface WakeTimeHistory {
    fun record(wokeAt: Instant)
    fun recent(): List<Instant>
}

/** [WakeTimeHistory] in SharedPreferences, the newest [capacity] wake-ups. */
class SharedPreferencesWakeTimeHistory(context: Context, private val capacity: Int = DEFAULT_CAPACITY) : WakeTimeHistory {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun record(wokeAt: Instant) {
        val kept = (recent() + wokeAt).takeLast(capacity)
        prefs.edit().putString(KEY_WAKES, kept.joinToString(SEPARATOR) { it.toEpochMilli().toString() }).apply()
    }

    override fun recent(): List<Instant> = prefs.getString(KEY_WAKES, null).orEmpty()
        .split(SEPARATOR).mapNotNull { it.toLongOrNull()?.let(Instant::ofEpochMilli) }

    private companion object {
        const val PREFS = "sleep_wake_times"
        const val KEY_WAKES = "wakes"
        const val SEPARATOR = ","
        const val DEFAULT_CAPACITY = 14
    }
}

/**
 * Decorator over the [next] store the sleep-stage source persists its open session in: it sees every stage change,
 * so it answers [SleepStageState] and records the wake-up of each night of at least [minNight] into [wakes] (where the
 * closing wake began, as the segmenter ends the session there).
 */
class SleepTrackingSessionStore(
    private val next: OpenSleepSessionStore,
    private val wakes: WakeTimeHistory,
    private val minNight: Duration,
) : OpenSleepSessionStore, SleepStageState {

    @Volatile
    private var session: OpenSleepSession? = null

    @Volatile
    private var lastWakeEndedAt: Instant? = null

    override fun load(): OpenSleepSession? = next.load().also { session = it }

    override fun save(session: OpenSleepSession?) {
        val previous = this.session
        if (previous != null && session == null) recordWake(previous)
        lastWakeEndedAt = when {
            session == null -> null
            previous?.stage == SleepStage.AWAKE && session.stage != SleepStage.AWAKE -> session.stageStart
            else -> lastWakeEndedAt
        }
        this.session = session
        next.save(session)
    }

    override fun current(): CurrentSleepStage? = session?.let { CurrentSleepStage(it.stage, it.stageStart, lastWakeEndedAt) }

    private fun recordWake(ended: OpenSleepSession) {
        val wokeAt = if (ended.stage == SleepStage.AWAKE) ended.stageStart else ended.lastEventAt
        if (Duration.between(ended.start, wokeAt) >= minNight) wakes.record(wokeAt)
    }
}

/**
 * Open while the wearer sleeps (not in an AWAKE stage) and the last wake inside the session ended at least [settle]
 * ago: the pulse of an arousal would read as a pressure rise. Open when the watch reports no stages.
 */
class SettledSleepStageGate(
    private val stages: SleepStageState,
    private val settle: Duration,
    private val clock: Clock,
) : WindowGate {

    override fun isOpen(): Boolean {
        val current = stages.current() ?: return true
        if (current.stage == SleepStage.AWAKE) return false
        val wakeEnded = current.lastWakeEndedAt ?: return true
        return Duration.between(wakeEnded, clock.instant()) >= settle
    }
}

/**
 * Morning windows around the wearer's usual wake-up (median time of day of [wakes], once [MIN_NIGHTS] are known):
 * the [beforeWake] span ahead of it and the [afterWake] span after the last recorded wake-up, where the morning
 * pressure surge happens.
 */
class MorningWindows(
    private val wakes: WakeTimeHistory,
    private val beforeWake: Duration,
    private val afterWake: Duration,
    private val zone: () -> ZoneId,
) {

    fun isBeforeUsualWake(now: Instant): Boolean {
        val usual = usualWakeMinute() ?: return false
        val minutesAhead = Math.floorMod(usual - minuteOfDay(now), MINUTES_PER_DAY)
        return minutesAhead <= beforeWake.toMinutes()
    }

    fun isAfterWake(now: Instant): Boolean {
        val last = wakes.recent().lastOrNull() ?: return false
        return !now.isBefore(last) && Duration.between(last, now) <= afterWake
    }

    private fun usualWakeMinute(): Int? {
        val minutes = wakes.recent().map(::minuteOfDay).sorted()
        return if (minutes.size < MIN_NIGHTS) null else minutes[minutes.size / 2]
    }

    private fun minuteOfDay(at: Instant): Int = at.atZone(zone()).toLocalTime().let { it.hour * MINUTES_PER_HOUR + it.minute }

    private companion object {
        const val MIN_NIGHTS = 3
        const val MINUTES_PER_HOUR = 60
        const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    }
}
