package org.sand.ticwatch.phone.spaceweather

import org.sand.ticwatch.phone.datalayer.WatchStatePublisher

/** Fetches fresh space weather, keeps it and passes it on to the watch; a failed fetch keeps the old copy. */
class SpaceWeatherRefresher(
    private val source: SpaceWeatherSource,
    private val store: SpaceWeatherStore,
    private val publisher: WatchStatePublisher,
) {
    suspend fun refresh(): SpaceWeather {
        val weather = source.fetch()
        store.save(weather)
        publisher.publish()
        return weather
    }
}
