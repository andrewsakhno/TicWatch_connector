package org.sand.ticwatch.phone.watchsettings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Battery-relevant watch features the user can switch on from either device. [changedAtEpochMs] is when the user
 * last changed them (0 = never), so both sides keep the newest copy (mirrored as watch-app's `WatchSettings`).
 */
data class WatchSettings(
    val autoBloodPressure: Boolean = false,
    val logCapture: Boolean = false,
    /** Snoring analysis during sleep, on [snoreMicrophone]'s microphone. */
    val snoreAnalysis: Boolean = false,
    val snoreMicrophone: SnoreMicrophone = SnoreMicrophone.WATCH,
    val changedAtEpochMs: Long = 0,
)

/** Which device listens for snoring: the watch costs its battery, the phone must lie next to the bed. */
enum class SnoreMicrophone(val wireValue: String, val label: String) {
    WATCH("watch", "Watch"),
    PHONE("phone", "Phone");

    fun next(): SnoreMicrophone = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(wireValue: String?): SnoreMicrophone = entries.firstOrNull { it.wireValue == wireValue } ?: WATCH
    }
}

/** JSON on [org.sand.ticwatch.phone.datalayer.DataLayerPaths.MESSAGE_WATCH_SETTINGS], both directions. */
object WatchSettingsPayload {
    private const val AUTO_BLOOD_PRESSURE = "auto_blood_pressure"
    private const val LOG_CAPTURE = "log_capture"
    private const val SNORE_ANALYSIS = "snore_analysis"
    private const val SNORE_MICROPHONE = "snore_microphone"
    private const val CHANGED_AT = "changed_at"

    fun encode(settings: WatchSettings): String = JSONObject()
        .put(AUTO_BLOOD_PRESSURE, settings.autoBloodPressure)
        .put(LOG_CAPTURE, settings.logCapture)
        .put(SNORE_ANALYSIS, settings.snoreAnalysis)
        .put(SNORE_MICROPHONE, settings.snoreMicrophone.wireValue)
        .put(CHANGED_AT, settings.changedAtEpochMs)
        .toString()

    // Snore keys are optional: a peer built before them sends none.
    fun decode(payload: String): WatchSettings = JSONObject(payload).let {
        WatchSettings(
            autoBloodPressure = it.getBoolean(AUTO_BLOOD_PRESSURE),
            logCapture = it.getBoolean(LOG_CAPTURE),
            snoreAnalysis = it.optBoolean(SNORE_ANALYSIS, false),
            snoreMicrophone = SnoreMicrophone.of(it.optString(SNORE_MICROPHONE).ifEmpty { null }),
            changedAtEpochMs = it.getLong(CHANGED_AT),
        )
    }
}

/** The phone's copy of the watch's [WatchSettings]; screens observe [settings]. */
interface WatchSettingsStore {
    val settings: StateFlow<WatchSettings>
    fun save(settings: WatchSettings)
}

/**
 * [WatchSettingsStore] in SharedPreferences, one per process: every `AppContainer` (services, activity)
 * shares the same flow. First access reads the preferences file - call off the main thread.
 */
class SharedPreferencesWatchSettingsStore private constructor(private val prefs: SharedPreferences) : WatchSettingsStore {

    private val state = MutableStateFlow(read())
    override val settings: StateFlow<WatchSettings> = state

    override fun save(settings: WatchSettings) {
        prefs.edit()
            .putBoolean(KEY_AUTO_BLOOD_PRESSURE, settings.autoBloodPressure)
            .putBoolean(KEY_LOG_CAPTURE, settings.logCapture)
            .putBoolean(KEY_SNORE_ANALYSIS, settings.snoreAnalysis)
            .putString(KEY_SNORE_MICROPHONE, settings.snoreMicrophone.wireValue)
            .putLong(KEY_CHANGED_AT, settings.changedAtEpochMs)
            .apply()
        state.value = settings
    }

    private fun read(): WatchSettings = WatchSettings(
        autoBloodPressure = prefs.getBoolean(KEY_AUTO_BLOOD_PRESSURE, DEFAULTS.autoBloodPressure),
        logCapture = prefs.getBoolean(KEY_LOG_CAPTURE, DEFAULTS.logCapture),
        snoreAnalysis = prefs.getBoolean(KEY_SNORE_ANALYSIS, DEFAULTS.snoreAnalysis),
        snoreMicrophone = SnoreMicrophone.of(prefs.getString(KEY_SNORE_MICROPHONE, null)),
        changedAtEpochMs = prefs.getLong(KEY_CHANGED_AT, DEFAULTS.changedAtEpochMs),
    )

    companion object {
        private const val PREFS_NAME = "watch_settings"
        private const val KEY_AUTO_BLOOD_PRESSURE = "auto_blood_pressure"
        private const val KEY_LOG_CAPTURE = "log_capture"
        private const val KEY_SNORE_ANALYSIS = "snore_analysis"
        private const val KEY_SNORE_MICROPHONE = "snore_microphone"
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
