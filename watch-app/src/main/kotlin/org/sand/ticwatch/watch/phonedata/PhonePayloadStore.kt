package org.sand.ticwatch.watch.phonedata

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.bloodpressure.BloodPressureHistory
import org.sand.ticwatch.watch.bloodpressure.BloodPressureMeasureResult
import org.sand.ticwatch.watch.bloodpressure.BloodPressurePayloads
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.scores.HealthScoresPayload
import org.sand.ticwatch.watch.scores.HealthScoresSnapshot
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherPayload
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherSnapshot

/** Receives a JSON payload the phone pushed. */
fun interface PhonePayloadSink {
    fun accept(payload: String)
}

/** Latest decoded phone payload of one kind, kept across restarts; screens observe [latest]. */
interface PhonePayloadStore<T : Any> : PhonePayloadSink {
    val latest: StateFlow<T?>
}

/** [PhonePayloadStore] keeping the raw payload under [key]; a payload [decode] rejects is ignored. */
class SharedPreferencesPhonePayloadStore<T : Any>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val decode: (String) -> T,
) : PhonePayloadStore<T> {

    private val state = MutableStateFlow(prefs.getString(key, null)?.let(::decodeOrNull))
    override val latest: StateFlow<T?> = state

    override fun accept(payload: String) {
        val decoded = decodeOrNull(payload) ?: return
        prefs.edit().putString(key, payload).apply()
        state.value = decoded
    }

    private fun decodeOrNull(payload: String): T? =
        runCatching { decode(payload) }.onFailure { Log.w(TAG, "Malformed phone payload '$key'", it) }.getOrNull()

    private companion object {
        const val TAG = "PhonePayloadStore"
    }
}

/**
 * One store per payload kind per process: the router writes from a listener service while the dashboard observes
 * the same flows in the activity. First access reads the preferences file - call off the main thread.
 */
class PhonePayloadStores private constructor(prefs: SharedPreferences) {
    val healthScores: PhonePayloadStore<HealthScoresSnapshot> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_HEALTH_SCORES, HealthScoresPayload::decode)
    val bloodPressureHistory: PhonePayloadStore<BloodPressureHistory> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_BLOOD_PRESSURE_HISTORY, BloodPressurePayloads::decodeHistory)
    val bloodPressureResult: PhonePayloadStore<BloodPressureMeasureResult> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_BLOOD_PRESSURE_RESULT, BloodPressurePayloads::decodeResult)
    val spaceWeather: PhonePayloadStore<SpaceWeatherSnapshot> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_SPACE_WEATHER, SpaceWeatherPayload::decode)

    /** Where each phone -> watch payload path is stored, for the message router. */
    val sinksByPath: Map<String, PhonePayloadSink> = mapOf(
        DataLayerPaths.MESSAGE_HEALTH_SCORES to healthScores,
        DataLayerPaths.MESSAGE_BLOOD_PRESSURE_HISTORY to bloodPressureHistory,
        DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT to bloodPressureResult,
        DataLayerPaths.MESSAGE_SPACE_WEATHER to spaceWeather,
    )

    companion object {
        // Same file and key the scores-only store used, so a stored snapshot survives the update.
        private const val PREFS_NAME = "health_scores"
        private const val KEY_HEALTH_SCORES = "payload"
        private const val KEY_BLOOD_PRESSURE_HISTORY = "blood_pressure_history"
        private const val KEY_BLOOD_PRESSURE_RESULT = "blood_pressure_result"
        private const val KEY_SPACE_WEATHER = "space_weather"

        @Volatile private var instance: PhonePayloadStores? = null

        fun get(context: Context): PhonePayloadStores = instance ?: synchronized(this) {
            instance ?: PhonePayloadStores(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
