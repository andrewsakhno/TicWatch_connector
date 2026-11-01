package org.sand.ticwatch.watch.sensors

import android.content.Context
import org.sand.ticwatch.watch.bloodpressure.RecentStepsGate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** How long a wake may last and still stay inside its sleep session (Strategy). */
fun interface WakeMergePolicy {
    fun maxWake(wakeStart: Instant): Duration
}

/**
 * Tolerates a [nightMaxWake] wake that begins between [nightStart] and [nightEnd] local time (a night may be
 * interrupted, a bathroom trip is still the same night), and only a [dayMaxWake] one otherwise, so two
 * separate naps stay separate.
 */
class NightAwareWakeMergePolicy(
    private val nightStart: LocalTime,
    private val nightEnd: LocalTime,
    private val nightMaxWake: Duration,
    private val dayMaxWake: Duration,
    zone: () -> ZoneId = ZoneId::systemDefault,
) : WakeMergePolicy {

    private val night = NightHours(nightStart, nightEnd, zone)

    override fun maxWake(wakeStart: Instant): Duration = if (night.contains(wakeStart)) nightMaxWake else dayMaxWake
}

/** The local hours from [start] to [end], across midnight. */
class NightHours(
    private val start: LocalTime,
    private val end: LocalTime,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    fun contains(at: Instant): Boolean {
        val time = at.atZone(zone()).toLocalTime()
        return time >= start || time < end
    }
}

/** Evidence from outside the sleep sensor that the wearer is up, polled while a session is open. */
fun interface WakeEvidence {
    fun isUp(): Boolean
}

/** The watch has been off the wrist for at least [minOffBody]. */
class OffBodyWakeEvidence(
    private val offBody: OffBodySince,
    private val minOffBody: Duration,
    private val clock: Clock,
) : WakeEvidence {

    override fun isUp(): Boolean {
        val since = offBody.offBodySince() ?: return false
        return Duration.between(since, clock.instant()) >= minOffBody
    }
}

/** More steps within [walking]'s window than it allows: walking around, not a trip to the bathroom. */
class WalkingWakeEvidence(private val walking: RecentStepsGate) : WakeEvidence {
    override fun isUp(): Boolean = !walking.isOpen()
}

/** Keeps the [OpenSleepSession] across service restarts. */
interface OpenSleepSessionStore {
    fun load(): OpenSleepSession?
    fun save(session: OpenSleepSession?)
}

class SharedPreferencesOpenSleepSessionStore(context: Context) : OpenSleepSessionStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): OpenSleepSession? {
        if (!prefs.contains(KEY_START)) return null
        val stage = SleepStage.fromCode(prefs.getInt(KEY_STAGE, UNKNOWN_STAGE)) ?: return null
        return OpenSleepSession(
            start = Instant.ofEpochMilli(prefs.getLong(KEY_START, 0L)),
            stage = stage,
            stageStart = Instant.ofEpochMilli(prefs.getLong(KEY_STAGE_START, 0L)),
            lastEventAt = Instant.ofEpochMilli(prefs.getLong(KEY_LAST_EVENT_AT, 0L)),
            slept = Duration.ofMillis(prefs.getLong(KEY_SLEPT_MS, 0L)),
            sensorLeftSleep = prefs.getBoolean(KEY_SENSOR_LEFT_SLEEP, false),
        )
    }

    override fun save(session: OpenSleepSession?) {
        val editor = prefs.edit().clear()
        if (session != null) {
            editor.putLong(KEY_START, session.start.toEpochMilli())
                .putInt(KEY_STAGE, session.stage.code)
                .putLong(KEY_STAGE_START, session.stageStart.toEpochMilli())
                .putLong(KEY_LAST_EVENT_AT, session.lastEventAt.toEpochMilli())
                .putLong(KEY_SLEPT_MS, session.slept.toMillis())
                .putBoolean(KEY_SENSOR_LEFT_SLEEP, session.sensorLeftSleep)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS = "open_sleep_session"
        const val KEY_START = "start"
        const val KEY_STAGE = "stage"
        const val KEY_STAGE_START = "stage_start"
        const val KEY_LAST_EVENT_AT = "last_event_at"
        const val KEY_SLEPT_MS = "slept_ms"
        const val KEY_SENSOR_LEFT_SLEEP = "sensor_left_sleep"
        const val UNKNOWN_STAGE = -1
    }
}
