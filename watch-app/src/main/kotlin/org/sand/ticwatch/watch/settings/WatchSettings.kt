package org.sand.ticwatch.watch.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.time.Duration
import org.sand.ticwatch.watch.R

/**
 * Battery-relevant watch features the user can switch on from either device. [changedAtEpochMs] is when the user
 * last changed them (0 = never), so both sides keep the newest copy (mirrored as phone-app's `WatchSettings`).
 */
data class WatchSettings(
    val autoBloodPressure: Boolean = false,
    /** Blood pressure every 20 min inside a sensor-detected sleep session (estimated on the watch) and after waking. */
    val nightBloodPressure: Boolean = false,
    val logCapture: Boolean = false,
    /** Snoring analysis during sleep, on [snoreMicrophone]'s microphone. */
    val snoreAnalysis: Boolean = false,
    val snoreMicrophone: SnoreMicrophone = SnoreMicrophone.WATCH,
    /** Raw pulse wave all night for pulse amplitude drops (apnea arousals); keeps the PPG at full rate. */
    val nightPulseWave: Boolean = false,
    /** Pauses the night monitor between its epochs (less PPG), so desaturations in the pauses go unseen. */
    val nightMonitorIntermittent: Boolean = false,
    /** IDLE cadence of the daytime RMSSD + breathing window; shorter costs a 3-minute PPG window each time. */
    val daytimeHrvInterval: DaytimeHrvInterval = DaytimeHrvInterval.DEFAULT,
    /** Brisk walking this long becomes a workout, on the watch and in the phone's detection alike. */
    val autoWorkoutMinutes: Int = AutoWorkoutDelay.DEFAULT_MINUTES,
    /** BCP 47 tag of the app language picked on the phone ([LANGUAGE_SYSTEM] = follow the device). */
    val language: String = LANGUAGE_SYSTEM,
    val changedAtEpochMs: Long = 0,
)

/** [WatchSettings.language] value meaning "use the device's own language". */
const val LANGUAGE_SYSTEM = ""

/** How often RMSSD and breathing rate are measured by day while nothing is boosted; [duration] is the IDLE cadence. */
enum class DaytimeHrvInterval(val minutes: Int) {
    MIN_30(30),
    MIN_15(15),
    MIN_10(10),
    MIN_5(5);

    val duration: Duration get() = Duration.ofMinutes(minutes.toLong())

    fun next(): DaytimeHrvInterval = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = MIN_30

        fun of(minutes: Int): DaytimeHrvInterval = entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

/**
 * How long a brisk walk lasts before it counts as a workout: the watch starts (or offers) one, the phone records a
 * missed one afterwards. Rest calculations treat only workouts as activity, so this also bounds what "at rest" excludes.
 */
object AutoWorkoutDelay {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 99
    const val DEFAULT_MINUTES = 10

    /** A stored or received value, clamped to the range the setting offers. */
    fun of(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
}

/** [WatchSettings.autoWorkoutMinutes] as a duration. */
val WatchSettings.autoWorkoutDelay: Duration get() = Duration.ofMinutes(autoWorkoutMinutes.toLong())

/** Which device listens for snoring: the watch costs its battery, the phone must lie next to the bed. */
enum class SnoreMicrophone(val wireValue: String, @StringRes val label: Int) {
    WATCH("watch", R.string.microphone_watch),
    PHONE("phone", R.string.microphone_phone);

    fun next(): SnoreMicrophone = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(wireValue: String?): SnoreMicrophone = entries.firstOrNull { it.wireValue == wireValue } ?: WATCH
    }
}

/** JSON on [org.sand.ticwatch.watch.datalayer.DataLayerPaths.MESSAGE_WATCH_SETTINGS], both directions. */
object WatchSettingsPayload {
    private const val AUTO_BLOOD_PRESSURE = "auto_blood_pressure"
    private const val NIGHT_BLOOD_PRESSURE = "night_blood_pressure"
    private const val LOG_CAPTURE = "log_capture"
    private const val SNORE_ANALYSIS = "snore_analysis"
    private const val SNORE_MICROPHONE = "snore_microphone"
    private const val NIGHT_PULSE_WAVE = "night_pulse_wave"
    private const val NIGHT_MONITOR_INTERMITTENT = "night_monitor_intermittent"
    private const val HRV_INTERVAL_MINUTES = "hrv_interval_minutes"
    private const val AUTO_WORKOUT_MINUTES = "auto_workout_minutes"
    private const val LANGUAGE = "language"
    private const val CHANGED_AT = "changed_at"

    fun encode(settings: WatchSettings): String = JSONObject()
        .put(AUTO_BLOOD_PRESSURE, settings.autoBloodPressure)
        .put(NIGHT_BLOOD_PRESSURE, settings.nightBloodPressure)
        .put(LOG_CAPTURE, settings.logCapture)
        .put(SNORE_ANALYSIS, settings.snoreAnalysis)
        .put(SNORE_MICROPHONE, settings.snoreMicrophone.wireValue)
        .put(NIGHT_PULSE_WAVE, settings.nightPulseWave)
        .put(NIGHT_MONITOR_INTERMITTENT, settings.nightMonitorIntermittent)
        .put(HRV_INTERVAL_MINUTES, settings.daytimeHrvInterval.minutes)
        .put(AUTO_WORKOUT_MINUTES, settings.autoWorkoutMinutes)
        .put(LANGUAGE, settings.language)
        .put(CHANGED_AT, settings.changedAtEpochMs)
        .toString()

    // Night blood pressure, snore, pulse wave, night monitor, HRV interval and language keys are optional: a peer built before them sends none.
    fun decode(payload: String): WatchSettings = JSONObject(payload).let {
        WatchSettings(
            autoBloodPressure = it.getBoolean(AUTO_BLOOD_PRESSURE),
            nightBloodPressure = it.optBoolean(NIGHT_BLOOD_PRESSURE, false),
            logCapture = it.getBoolean(LOG_CAPTURE),
            snoreAnalysis = it.optBoolean(SNORE_ANALYSIS, false),
            snoreMicrophone = SnoreMicrophone.of(it.optString(SNORE_MICROPHONE).ifEmpty { null }),
            nightPulseWave = it.optBoolean(NIGHT_PULSE_WAVE, false),
            nightMonitorIntermittent = it.optBoolean(NIGHT_MONITOR_INTERMITTENT, false),
            daytimeHrvInterval = DaytimeHrvInterval.of(it.optInt(HRV_INTERVAL_MINUTES, DaytimeHrvInterval.DEFAULT.minutes)),
            // Optional too: a peer built before it sends none.
            autoWorkoutMinutes = AutoWorkoutDelay.of(it.optInt(AUTO_WORKOUT_MINUTES, AutoWorkoutDelay.DEFAULT_MINUTES)),
            language = it.optString(LANGUAGE, LANGUAGE_SYSTEM),
            changedAtEpochMs = it.getLong(CHANGED_AT),
        )
    }
}

/** The watch's current [WatchSettings]; components observe [settings]. */
interface WatchSettingsStore {
    val settings: StateFlow<WatchSettings>
    fun save(settings: WatchSettings)
}

/**
 * [WatchSettingsStore] in SharedPreferences, one per process: the application, the collection service and the
 * activity share the same flow. First access reads the preferences file - call off the main thread.
 */
class SharedPreferencesWatchSettingsStore private constructor(private val prefs: SharedPreferences) : WatchSettingsStore {

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<WatchSettings> = state

    override fun save(settings: WatchSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_BLOOD_PRESSURE, settings.autoBloodPressure)
            .putBoolean(KEY_NIGHT_BLOOD_PRESSURE, settings.nightBloodPressure)
            .putBoolean(KEY_LOG_CAPTURE, settings.logCapture)
            .putBoolean(KEY_SNORE_ANALYSIS, settings.snoreAnalysis)
            .putString(KEY_SNORE_MICROPHONE, settings.snoreMicrophone.wireValue)
            .putBoolean(KEY_NIGHT_PULSE_WAVE, settings.nightPulseWave)
            .putBoolean(KEY_NIGHT_MONITOR_INTERMITTENT, settings.nightMonitorIntermittent)
            .putInt(KEY_HRV_INTERVAL_MINUTES, settings.daytimeHrvInterval.minutes)
            .putInt(KEY_AUTO_WORKOUT_MINUTES, settings.autoWorkoutMinutes)
            .putString(KEY_LANGUAGE, settings.language)
            .putLong(KEY_CHANGED_AT, settings.changedAtEpochMs)
            .apply()
        state.value = settings
    }

    private fun read(): WatchSettings = WatchSettings(
        autoBloodPressure = prefs.getBoolean(KEY_AUTO_BLOOD_PRESSURE, DEFAULTS.autoBloodPressure),
        nightBloodPressure = prefs.getBoolean(KEY_NIGHT_BLOOD_PRESSURE, DEFAULTS.nightBloodPressure),
        logCapture = prefs.getBoolean(KEY_LOG_CAPTURE, DEFAULTS.logCapture),
        snoreAnalysis = prefs.getBoolean(KEY_SNORE_ANALYSIS, DEFAULTS.snoreAnalysis),
        snoreMicrophone = SnoreMicrophone.of(prefs.getString(KEY_SNORE_MICROPHONE, null)),
        nightPulseWave = prefs.getBoolean(KEY_NIGHT_PULSE_WAVE, DEFAULTS.nightPulseWave),
        nightMonitorIntermittent = prefs.getBoolean(KEY_NIGHT_MONITOR_INTERMITTENT, DEFAULTS.nightMonitorIntermittent),
        daytimeHrvInterval = DaytimeHrvInterval.of(prefs.getInt(KEY_HRV_INTERVAL_MINUTES, DEFAULTS.daytimeHrvInterval.minutes)),
        autoWorkoutMinutes = AutoWorkoutDelay.of(prefs.getInt(KEY_AUTO_WORKOUT_MINUTES, DEFAULTS.autoWorkoutMinutes)),
        language = prefs.getString(KEY_LANGUAGE, null) ?: DEFAULTS.language,
        changedAtEpochMs = prefs.getLong(KEY_CHANGED_AT, DEFAULTS.changedAtEpochMs),
    )

    companion object {
        private const val PREFS_NAME = "watch_settings"
        private const val KEY_AUTO_BLOOD_PRESSURE = "auto_blood_pressure"
        private const val KEY_NIGHT_BLOOD_PRESSURE = "night_blood_pressure"
        private const val KEY_LOG_CAPTURE = "log_capture"
        private const val KEY_SNORE_ANALYSIS = "snore_analysis"
        private const val KEY_SNORE_MICROPHONE = "snore_microphone"
        private const val KEY_NIGHT_PULSE_WAVE = "night_pulse_wave"
        private const val KEY_NIGHT_MONITOR_INTERMITTENT = "night_monitor_intermittent"
        private const val KEY_HRV_INTERVAL_MINUTES = "hrv_interval_minutes"
        private const val KEY_AUTO_WORKOUT_MINUTES = "auto_workout_minutes"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CHANGED_AT = "changed_at"
        private val DEFAULTS = WatchSettings()

        @Volatile private var instance: SharedPreferencesWatchSettingsStore? = null

        fun get(context: Context): SharedPreferencesWatchSettingsStore = instance ?: synchronized(this) {
            instance ?: SharedPreferencesWatchSettingsStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
