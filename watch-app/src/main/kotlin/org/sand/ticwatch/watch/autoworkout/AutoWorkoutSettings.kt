package org.sand.ticwatch.watch.autoworkout

import android.content.Context

/** What happens when a long brisk walk is detected. */
enum class AutoWorkoutMode(val key: String, val label: String) {
    OFF("off", "Off"),
    ASK("ask", "Ask first"),
    AUTO("auto", "Automatic");

    /** The mode a tap on the setting switches to. */
    fun next(): AutoWorkoutMode = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = ASK

        fun fromKey(key: String?): AutoWorkoutMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

interface AutoWorkoutSettings {
    fun mode(): AutoWorkoutMode
    fun setMode(mode: AutoWorkoutMode)
}

/** [AutoWorkoutSettings] in SharedPreferences, shared by MainActivity and the collection service. */
class SharedPreferencesAutoWorkoutSettings(context: Context) : AutoWorkoutSettings {

    private val prefs by lazy { context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE) }

    override fun mode(): AutoWorkoutMode = AutoWorkoutMode.fromKey(prefs.getString(KEY_MODE, null))

    override fun setMode(mode: AutoWorkoutMode) {
        prefs.edit().putString(KEY_MODE, mode.key).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_workout_settings"
        const val KEY_MODE = "mode"
    }
}
