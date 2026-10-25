package org.sand.ticwatch.phone

import android.content.Context
import com.google.android.gms.wearable.Wearable
import org.sand.ticwatch.phone.datalayer.TransportWatchAppManager
import org.sand.ticwatch.phone.datalayer.WatchAppManager
import org.sand.ticwatch.phone.health.HealthConnectRecordWriter
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.RestingHeartRateEstimator
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.network.BackendApi
import org.sand.ticwatch.phone.network.BackendApiFactory
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.TransportFactory

/**
 * Minimal composition root: builds concrete implementations once and hands them out
 * as their interfaces (DIP), so Activities/ViewModels take collaborators through
 * their constructor instead of instantiating framework clients themselves.
 */
class AppContainer(context: Context, backendBaseUrl: String = "http://10.0.2.2:8080/") {
    val deviceLinkTransport: DeviceLinkTransport = TransportFactory.create(context)

    val watchAppManager: WatchAppManager = TransportWatchAppManager(
        nodeClient = Wearable.getNodeClient(context),
        transport = deviceLinkTransport,
    )

    val healthRecordWriter: HealthRecordWriter = HealthConnectRecordWriter(context)

    val sensorDataSyncWorker: SensorDataSyncWorker = SensorDataSyncWorker(
        healthRecordWriter = healthRecordWriter,
        restingHeartRateEstimator = RestingHeartRateEstimator(),
    )

    val backendApi: BackendApi = BackendApiFactory.create(backendBaseUrl)
}
