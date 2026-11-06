package org.sand.ticwatch.watch.phonedata

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.wear.tiles.TileService
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.watch.bloodpressure.BloodPressureHistory
import org.sand.ticwatch.watch.bloodpressure.BloodPressureMeasureResult
import org.sand.ticwatch.watch.bloodpressure.BloodPressureCalibrationPayload
import org.sand.ticwatch.watch.bloodpressure.BloodPressurePayloads
import org.sand.ticwatch.watch.bloodpressure.WatchBloodPressureCalibration
import org.sand.ticwatch.watch.datalayer.DataLayerPaths
import org.sand.ticwatch.watch.readiness.CardioReadinessPayload
import org.sand.ticwatch.watch.readiness.CardioReadinessSnapshot
import org.sand.ticwatch.watch.scores.HealthScoresPayload
import org.sand.ticwatch.watch.sensors.adaptive.HeartRateCorridorPayload
import org.sand.ticwatch.watch.sensors.adaptive.HeartRateCorridorSnapshot
import org.sand.ticwatch.watch.scores.HealthScoresSnapshot
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherPayload
import org.sand.ticwatch.watch.spaceweather.SpaceWeatherSnapshot
import org.sand.ticwatch.watch.tile.CardioReadinessTileService
import org.sand.ticwatch.watch.tile.TicWatchTileService

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

/** Passes each payload to [store], then runs [afterAccept] (e.g. asks a tile showing it to re-render). */
private class NotifyingPhonePayloadSink(
    private val store: PhonePayloadSink,
    private val afterAccept: () -> Unit,
) : PhonePayloadSink {
    override fun accept(payload: String) {
        store.accept(payload)
        afterAccept()
    }
}

/**
 * One store per payload kind per process: the router writes from a listener service while the dashboard observes
 * the same flows in the activity. First access reads the preferences file - call off the main thread.
 * [onCardioReadiness] runs after each readiness payload, so the tiles showing it follow the phone.
 */
class PhonePayloadStores private constructor(prefs: SharedPreferences, onCardioReadiness: () -> Unit) {
    val healthScores: PhonePayloadStore<HealthScoresSnapshot> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_HEALTH_SCORES, HealthScoresPayload::decode)
    val bloodPressureHistory: PhonePayloadStore<BloodPressureHistory> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_BLOOD_PRESSURE_HISTORY, BloodPressurePayloads::decodeHistory)
    val bloodPressureResult: PhonePayloadStore<BloodPressureMeasureResult> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_BLOOD_PRESSURE_RESULT, BloodPressurePayloads::decodeResult)
    val bloodPressureCalibration: PhonePayloadStore<WatchBloodPressureCalibration> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_BLOOD_PRESSURE_CALIBRATION, BloodPressureCalibrationPayload::decode)
    val spaceWeather: PhonePayloadStore<SpaceWeatherSnapshot> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_SPACE_WEATHER, SpaceWeatherPayload::decode)
    val cardioReadiness: PhonePayloadStore<CardioReadinessSnapshot> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_CARDIO_READINESS, CardioReadinessPayload::decode)
    val heartRateCorridor: PhonePayloadStore<HeartRateCorridorSnapshot> =
        SharedPreferencesPhonePayloadStore(prefs, KEY_HEART_RATE_CORRIDOR, HeartRateCorridorPayload::decode)

    /** Where each phone -> watch payload path is stored, for the message router. */
    val sinksByPath: Map<String, PhonePayloadSink> = mapOf(
        DataLayerPaths.MESSAGE_HEALTH_SCORES to healthScores,
        DataLayerPaths.MESSAGE_BLOOD_PRESSURE_HISTORY to bloodPressureHistory,
        DataLayerPaths.MESSAGE_BLOOD_PRESSURE_MEASURE_RESULT to bloodPressureResult,
        DataLayerPaths.MESSAGE_BLOOD_PRESSURE_CALIBRATION to bloodPressureCalibration,
        DataLayerPaths.MESSAGE_SPACE_WEATHER to spaceWeather,
        DataLayerPaths.MESSAGE_CARDIO_READINESS to NotifyingPhonePayloadSink(cardioReadiness, onCardioReadiness),
        DataLayerPaths.MESSAGE_HEART_RATE_CORRIDOR to heartRateCorridor,
    )

    companion object {
        // Same file and key the scores-only store used, so a stored snapshot survives the update.
        private const val PREFS_NAME = "health_scores"
        private const val KEY_HEALTH_SCORES = "payload"
        private const val KEY_BLOOD_PRESSURE_HISTORY = "blood_pressure_history"
        private const val KEY_BLOOD_PRESSURE_RESULT = "blood_pressure_result"
        private const val KEY_BLOOD_PRESSURE_CALIBRATION = "blood_pressure_calibration"
        private const val KEY_SPACE_WEATHER = "space_weather"
        private const val KEY_CARDIO_READINESS = "cardio_readiness"
        private const val KEY_HEART_RATE_CORRIDOR = "heart_rate_corridor"

        @Volatile private var instance: PhonePayloadStores? = null

        fun get(context: Context): PhonePayloadStores = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: PhonePayloadStores(
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                onCardioReadiness = {
                    val updater = TileService.getUpdater(app)
                    updater.requestUpdate(CardioReadinessTileService::class.java)
                    updater.requestUpdate(TicWatchTileService::class.java)
                },
            ).also { instance = it }
        }
    }
}
