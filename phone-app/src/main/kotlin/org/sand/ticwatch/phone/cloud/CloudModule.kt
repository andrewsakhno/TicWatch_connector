package org.sand.ticwatch.phone.cloud

import android.content.Context
import org.sand.ticwatch.phone.ecg.EcgModule
import org.sand.ticwatch.phone.history.SensorHistoryModule

/**
 * Wiring for cloud storage, shared by `AppContainer`, the history/ECG purges and the reflectively created
 * [CloudSyncWorker]. Settings, status and the HTTP clients are process-wide.
 */
object CloudModule {

    fun settings(context: Context): SharedPreferencesCloudSettings = SharedPreferencesCloudSettings.get(context)

    fun status(context: Context): CloudSyncStatusStore = SharedPreferencesCloudSyncStatusStore.get(context)

    fun retentionHold(context: Context): CloudRetentionHold = SettingsCloudRetentionHold(settings(context))

    @Volatile private var apiProvider: CloudApiProvider? = null

    fun apis(context: Context): CloudApiProvider = apiProvider ?: synchronized(this) {
        apiProvider ?: settings(context).let { settings ->
            RetrofitCloudApiProvider(deviceId = { settings.deviceId }, apiToken = settings::apiTokenNow)
        }.also { apiProvider = it }
    }

    /** Everything received from the watch: raw samples of every type, sleep tables, workout routes, rhythm strips. */
    fun channels(context: Context): List<CloudUploadChannel> = listOf(
        CloudUploadChannels.samples(SensorHistoryModule.store(context)),
        CloudUploadChannels.sleepSessions(SensorHistoryModule.sleepSessionOutbox(context)),
        CloudUploadChannels.sleepStages(SensorHistoryModule.sleepStageOutbox(context)),
        CloudUploadChannels.routePoints(SensorHistoryModule.routeStore(context)),
        CloudUploadChannels.ecgRecordings(EcgModule.log(context)),
    )

    @Volatile private var engine: CloudSyncEngine? = null

    /** One per process, so its mutex really keeps rounds from overlapping. */
    fun engine(context: Context): CloudSyncEngine = engine ?: synchronized(this) {
        engine ?: settings(context).let { settings ->
            CloudSyncEngine(
                settings = settings,
                memory = settings,
                locator = SubnetCloudServerLocator(
                    settings, settings, PingCloudServerProbe(apis(context)), ConnectivityLocalNetworkAddress(context),
                ),
                apis = apis(context),
                channels = channels(context),
                status = status(context),
                clock = SensorHistoryModule.clock,
            )
        }.also { engine = it }
    }

    fun historyReader(context: Context): CloudHistoryReader =
        HttpCloudHistoryReader(settings(context), settings(context), apis(context), SensorHistoryModule.zone, SensorHistoryModule.clock)
}
