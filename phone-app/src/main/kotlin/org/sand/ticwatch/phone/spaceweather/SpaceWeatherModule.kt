package org.sand.ticwatch.phone.spaceweather

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.sand.ticwatch.phone.datalayer.WatchSpaceWeatherPublisher
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.TransportFactory

/** Space-weather object graph, shared by [org.sand.ticwatch.phone.AppContainer] and [SpaceWeatherRefreshWorker]. */
object SpaceWeatherModule {

    fun store(context: Context): SpaceWeatherStore = SharedPreferencesSpaceWeatherStore(context)

    fun publisher(store: SpaceWeatherStore, transport: DeviceLinkTransport): WatchSpaceWeatherPublisher =
        WatchSpaceWeatherPublisher(store, transport, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    fun refresher(context: Context): SpaceWeatherRefresher {
        val store = store(context)
        return SpaceWeatherRefresher(
            source = NoaaSpaceWeatherSource(
                UrlConnectionTextFetcher(),
                NoaaSpaceWeatherParser(),
                SpaceWeatherAssembler(SensorHistoryModule.zone),
                SensorHistoryModule.clock,
            ),
            store = store,
            publisher = publisher(store, TransportFactory.create(context)),
            alerter = GeomagneticAlerter(
                GeomagneticAlertPolicy(),
                SharedPreferencesGeomagneticAlertLedger(context),
                SystemGeomagneticAlertNotifier(context),
                SensorHistoryModule.clock,
            ),
        )
    }
}
