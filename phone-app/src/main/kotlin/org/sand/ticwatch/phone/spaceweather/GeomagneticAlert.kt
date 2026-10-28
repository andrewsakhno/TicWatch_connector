package org.sand.ticwatch.phone.spaceweather

import android.content.Context
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** An upcoming or ongoing geomagnetic disturbance: the highest Kp ahead and the 3-hour slot it peaks in. */
data class GeomagneticAlert(val maxKp: Double, val peakAt: Instant) {
    val level: GeomagneticLevel get() = GeomagneticLevel.of(maxKp)
}

/** Finds the peak Kp of the slots still running or starting within [HORIZON]; alerts from [ALERT_LEVEL] (Kp 4) up. */
class GeomagneticAlertPolicy {

    fun alertFor(weather: SpaceWeather, now: Instant): GeomagneticAlert? {
        val peak = weather.slots
            .filter { it.start.plus(SLOT_LENGTH).isAfter(now) && it.start.isBefore(now.plus(HORIZON)) }
            .maxByOrNull { it.kp }
            ?: return null
        return GeomagneticAlert(peak.kp, peak.start).takeIf { it.level >= ALERT_LEVEL }
    }

    companion object {
        /** Kp >= 4: an active disturbance; from Kp 5 (G1) on a storm. */
        val ALERT_LEVEL = GeomagneticLevel.ACTIVE
        val HORIZON: Duration = Duration.ofHours(48)
        private val SLOT_LENGTH = Duration.ofHours(3)
    }
}

/** The last alert shown, so a disturbance spanning several refreshes is announced once (again if it escalates). */
interface GeomagneticAlertLedger {
    fun lastLevel(): GeomagneticLevel?
    fun lastNotifiedAt(): Instant?
    fun record(level: GeomagneticLevel, at: Instant)
}

class SharedPreferencesGeomagneticAlertLedger(context: Context) : GeomagneticAlertLedger {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastLevel(): GeomagneticLevel? = prefs.getString(KEY_LEVEL, null)?.let { name ->
        GeomagneticLevel.entries.firstOrNull { it.name == name }
    }

    override fun lastNotifiedAt(): Instant? =
        if (prefs.contains(KEY_AT)) Instant.ofEpochMilli(prefs.getLong(KEY_AT, 0L)) else null

    override fun record(level: GeomagneticLevel, at: Instant) {
        prefs.edit().putString(KEY_LEVEL, level.name).putLong(KEY_AT, at.toEpochMilli()).apply()
    }

    private companion object {
        const val PREFS_NAME = "geomagnetic_alerts"
        const val KEY_LEVEL = "last_level"
        const val KEY_AT = "last_notified_at_epoch_ms"
    }
}

/** Shows a [GeomagneticAlert] to the user. */
fun interface GeomagneticAlertNotifier {
    fun notify(alert: GeomagneticAlert)
}

/**
 * Runs after each refresh: notifies when the policy finds a disturbance and it is either higher than the last one
 * announced or that one is older than [RENOTIFY_AFTER]. Blocking (preferences) - call off the main thread.
 */
class GeomagneticAlerter(
    private val policy: GeomagneticAlertPolicy,
    private val ledger: GeomagneticAlertLedger,
    private val notifier: GeomagneticAlertNotifier,
    private val clock: Clock,
) {
    fun check(weather: SpaceWeather) {
        val now = clock.instant()
        val alert = policy.alertFor(weather, now) ?: return
        val lastLevel = ledger.lastLevel()
        val lastAt = ledger.lastNotifiedAt()
        val recent = lastAt != null && Duration.between(lastAt, now) < RENOTIFY_AFTER
        if (recent && lastLevel != null && alert.level <= lastLevel) return
        notifier.notify(alert)
        ledger.record(alert.level, now)
    }

    companion object {
        val RENOTIFY_AFTER: Duration = Duration.ofHours(24)
    }
}
