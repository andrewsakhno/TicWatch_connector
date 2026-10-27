package org.sand.ticwatch.watch.bloodpressure

import android.content.Context

/** Whether blood pressure is requested automatically while the wearer rests. */
interface AutoBloodPressureSettings {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

/** [AutoBloodPressureSettings] in SharedPreferences, shared by MainActivity and the collection service. */
class SharedPreferencesAutoBloodPressureSettings(context: Context) : AutoBloodPressureSettings {

    private val prefs by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    override fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_blood_pressure_settings"
        const val KEY_ENABLED = "enabled"
        const val DEFAULT_ENABLED = true
    }
}
