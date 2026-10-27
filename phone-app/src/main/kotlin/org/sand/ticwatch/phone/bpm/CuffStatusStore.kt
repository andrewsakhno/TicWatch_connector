package org.sand.ticwatch.phone.bpm

import android.content.Context
import java.time.Instant

/** What the cuff last reported about itself, and when. */
data class CuffStatus(val batteryPercent: Int, val serial: String, val seenAt: Instant)

fun interface CuffStatusSource {
    fun cuffStatus(): CuffStatus?
}

fun interface CuffStatusSink {
    fun setCuffStatus(status: CuffStatus)
}

/** SharedPreferences-backed, so the dashboard (activity's container) sees what the link service's client stored. */
class SharedPreferencesCuffStatusStore(context: Context) : CuffStatusSource, CuffStatusSink {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun cuffStatus(): CuffStatus? {
        val serial = preferences.getString(KEY_SERIAL, null) ?: return null
        return CuffStatus(
            batteryPercent = preferences.getInt(KEY_BATTERY, 0),
            serial = serial,
            seenAt = Instant.ofEpochMilli(preferences.getLong(KEY_SEEN_AT, 0L)),
        )
    }

    override fun setCuffStatus(status: CuffStatus) {
        preferences.edit()
            .putInt(KEY_BATTERY, status.batteryPercent)
            .putString(KEY_SERIAL, status.serial)
            .putLong(KEY_SEEN_AT, status.seenAt.toEpochMilli())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "blood_pressure_cuff"
        const val KEY_BATTERY = "battery_percent"
        const val KEY_SERIAL = "serial"
        const val KEY_SEEN_AT = "seen_at_epoch_ms"
    }
}
