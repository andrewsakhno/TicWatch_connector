package org.sand.ticwatch.phone.spaceweather

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.sand.ticwatch.phone.datalayer.WatchSpaceWeatherPublisher
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.text.ResourcesTextResolver
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.TransportFactory

/** Space-weather object graph, shared by [org.sand.ticwatch.phone.AppContainer] and [SpaceWeatherRefreshWorker]. */
object SpaceWeatherModule {

    fun store(context: Context): SpaceWeatherStore = SharedPreferencesSpaceWeatherStore(context)

    fun publisher(store: SpaceWeatherStore, transport: DeviceLinkTransport, texts: TextResolver): WatchSpaceWeatherPublisher =
        WatchSpaceWeatherPublisher(store, transport, CoroutineScope(SupervisorJob() + Dispatchers.IO), texts)

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
            publisher = publisher(store, TransportFactory.create(context), ResourcesTextResolver { context.applicationContext.resources }),
            alerter = GeomagneticAlerter(
                GeomagneticAlertPolicy(),
                SharedPreferencesGeomagneticAlertLedger(context),
                SystemGeomagneticAlertNotifier(context),
                SensorHistoryModule.clock,
            ),
        )
    }
}
