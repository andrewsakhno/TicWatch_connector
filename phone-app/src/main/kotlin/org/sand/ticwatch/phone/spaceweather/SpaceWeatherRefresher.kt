package org.sand.ticwatch.phone.spaceweather

import org.sand.ticwatch.phone.datalayer.WatchStatePublisher

/**
 * Fetches fresh space weather, keeps it, passes it on to the watch and lets [alerter] warn about a disturbance;
 * a failed fetch keeps the old copy.
 */
class SpaceWeatherRefresher(
    private val source: SpaceWeatherSource,
    private val store: SpaceWeatherStore,
    private val publisher: WatchStatePublisher,
    private val alerter: GeomagneticAlerter,
) {
    suspend fun refresh(): SpaceWeather {
        val weather = source.fetch()
        store.save(weather)
        publisher.publish()
        alerter.check(weather)
        return weather
    }
}
