package org.sand.ticwatch.phone.spaceweather

import android.content.Context
import android.util.Log

/** The last fetched [SpaceWeather]; blocking (preferences file) - call off the main thread. */
interface SpaceWeatherStore {
    /** The stored [SpaceWeatherPayload] JSON, as sent to the watch. */
    fun payload(): String?
    fun load(): SpaceWeather?
    fun save(weather: SpaceWeather)
}

class SharedPreferencesSpaceWeatherStore(context: Context) : SpaceWeatherStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun payload(): String? = prefs.getString(KEY_PAYLOAD, null)

    override fun load(): SpaceWeather? = payload()?.let { stored ->
        runCatching { SpaceWeatherPayload.decode(stored) }
            .onFailure { Log.w(TAG, "Malformed stored space weather", it) }
            .getOrNull()
    }

    override fun save(weather: SpaceWeather) {
        prefs.edit().putString(KEY_PAYLOAD, SpaceWeatherPayload.encode(weather)).apply()
    }

    private companion object {
        const val TAG = "SpaceWeatherStore"
        const val PREFS_NAME = "space_weather"
        const val KEY_PAYLOAD = "payload"
    }
}
