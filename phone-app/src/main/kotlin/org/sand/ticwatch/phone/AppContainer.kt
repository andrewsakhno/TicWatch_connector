package org.sand.ticwatch.phone

import android.content.Context
import com.google.android.gms.wearable.Wearable
import org.sand.ticwatch.phone.datalayer.TransportWatchAppManager
import org.sand.ticwatch.phone.datalayer.WatchAppManager
import org.sand.ticwatch.phone.health.HealthConnectRecordWriter
import org.sand.ticwatch.phone.health.HealthRecordWriter
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import org.sand.ticwatch.phone.health.LegacyRecordCleanup
import org.sand.ticwatch.phone.health.SleepNightReplacement
import org.sand.ticwatch.phone.health.CircadianOffset
import org.sand.ticwatch.phone.health.CorrelatedBodyTemperatureEstimator
import org.sand.ticwatch.phone.health.DualHeatFluxCoreModel
import org.sand.ticwatch.phone.health.QualifiedCoreModel
import org.sand.ticwatch.phone.health.RestingHeartRateBaseline
import org.sand.ticwatch.phone.health.SkinOffsetCoreModel
import org.sand.ticwatch.phone.health.ExerciseRecoveryQualifier
import org.sand.ticwatch.phone.health.HeartRateElevationQualifier
import org.sand.ticwatch.phone.health.StepCadenceQualifier
import org.sand.ticwatch.phone.history.BodyTemperatureBackfill
import org.sand.ticwatch.phone.history.NightlySkinTemperature
import org.sand.ticwatch.phone.mcp.history.GetNightlySkinTemperatureTool
import org.sand.ticwatch.phone.health.RestingHeartRateEstimator
import org.sand.ticwatch.phone.health.SharedPreferencesDailyMinimumStore
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.health.SharedPreferencesUserProfileStore
import org.sand.ticwatch.phone.health.SpeedDeriver
import org.sand.ticwatch.phone.health.StepsCadenceDeriver
import org.sand.ticwatch.phone.health.BasalMetabolicRateDeriver
import org.sand.ticwatch.phone.health.MifflinStJeorFormula
import org.sand.ticwatch.phone.health.SharedPreferencesDailyValueLog
import org.sand.ticwatch.phone.health.UserProfileStore
import org.sand.ticwatch.phone.health.Vo2MaxEstimator
import org.sand.ticwatch.phone.history.AcknowledgingHealthRecordWriter
import org.sand.ticwatch.phone.history.HistoryRetentionStore
import org.sand.ticwatch.phone.history.SensorHistoryMaintenance
import org.sand.ticwatch.phone.history.SleepSessionRetentionStore
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.history.SensorHistoryPurger
import org.sand.ticwatch.phone.mcp.McpAgentSettingsStore
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.mcp.SharedPreferencesMcpAgentSettingsStore
import org.sand.ticwatch.phone.mcp.history.ExportSamplesTool
import org.sand.ticwatch.phone.mcp.history.GetDailyProfileTool
import org.sand.ticwatch.phone.mcp.history.GetDeliveryStatusTool
import org.sand.ticwatch.phone.mcp.history.GetExerciseRouteTool
import org.sand.ticwatch.phone.mcp.history.GetMultiSeriesTool
import org.sand.ticwatch.phone.mcp.history.GetRawSamplesTool
import org.sand.ticwatch.phone.mcp.history.GetSeriesTool
import org.sand.ticwatch.phone.mcp.history.GetSleepSessionsTool
import org.sand.ticwatch.phone.mcp.history.GetSummaryTool
import org.sand.ticwatch.phone.mcp.history.HistoryToolArguments
import org.sand.ticwatch.phone.mcp.history.HistoryToolFormat
import org.sand.ticwatch.phone.mcp.history.ListMetricsTool
import org.sand.ticwatch.phone.mcp.history.RollupResolutionSelector
import org.sand.ticwatch.phone.mcp.history.SensorMetricCatalog
import org.sand.ticwatch.phone.network.BackendApi
import org.sand.ticwatch.phone.route.ExerciseRouteLog
import org.sand.ticwatch.phone.route.ServiceWorkoutRouteRecorder
import org.sand.ticwatch.phone.route.WorkoutRouteRecorder
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

    private val exerciseRouteStore = SensorHistoryModule.routeStore(context)

    val exerciseRouteLog: ExerciseRouteLog = exerciseRouteStore

    private val sensorHistoryStore = SensorHistoryModule.store(context)

    val healthRecordWriter: HealthRecordWriter = HealthConnectRecordWriter(context, exerciseRouteStore, sensorHistoryStore)

    val workoutRouteRecorder: WorkoutRouteRecorder = ServiceWorkoutRouteRecorder(context)

    private val restingHeartRateState =
        context.getSharedPreferences(SharedPreferencesDailyMinimumStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    val sensorHistoryMaintenance: SensorHistoryMaintenance = sensorHistoryStore

    val historyRetentionStore: HistoryRetentionStore = SensorHistoryModule.retentionStore(context)

    val sleepSessionRetentionStore: SleepSessionRetentionStore = SensorHistoryModule.sleepSessionRetentionStore(context)

    val userProfileStore: UserProfileStore = SharedPreferencesUserProfileStore(context)

    val sensorHistoryPurger: SensorHistoryPurger =
        SensorHistoryPurger(
            sensorHistoryStore, exerciseRouteStore, historyRetentionStore, sleepSessionRetentionStore,
            SensorHistoryModule.clock, SensorHistoryModule.zone,
        )

    private val restingHeartRateMinimums = SharedPreferencesDailyMinimumStore(restingHeartRateState)

    private val acknowledgingHealthRecordWriter: HealthRecordWriter = AcknowledgingHealthRecordWriter(
        delegate = healthRecordWriter,
        historyLog = sensorHistoryStore,
        delivery = SensorHistoryModule.delivery(healthRecordWriter, sensorHistoryStore),
        clock = SensorHistoryModule.clock,
    )

    private val restingHeartRateBaseline = RestingHeartRateBaseline(restingHeartRateMinimums, SensorHistoryModule.zone)

    private val nightlySkinTemperature = NightlySkinTemperature(sensorHistoryStore, SensorHistoryModule.clock)

    // Movement disqualifies every model; a raised HR only the skin-only fallback, since the heat-flux
    // model reads a resting HR rise as fever.
    private val bodyTemperatureEstimator = CorrelatedBodyTemperatureEstimator(
        models = listOf(
            DualHeatFluxCoreModel(restingHeartRateBaseline, CircadianOffset(SensorHistoryModule.zone)),
            QualifiedCoreModel(
                qualifiers = listOf(HeartRateElevationQualifier(restingHeartRateBaseline)),
                delegate = SkinOffsetCoreModel(nightlySkinTemperature),
            ),
        ),
        qualifiers = listOf(ExerciseRecoveryQualifier(), StepCadenceQualifier()),
    )

    val sensorDataSyncWorker: SensorDataSyncWorker = SensorDataSyncWorker(
        healthRecordWriter = acknowledgingHealthRecordWriter,
        derivers = listOf(
            RestingHeartRateEstimator(store = restingHeartRateMinimums),
            Vo2MaxEstimator(profile = userProfileStore),
            SpeedDeriver(),
            StepsCadenceDeriver(),
            BasalMetabolicRateDeriver(userProfileStore, MifflinStJeorFormula(), SharedPreferencesDailyValueLog(context)),
            bodyTemperatureEstimator,
        ),
    )

    val bodyTemperatureBackfill = BodyTemperatureBackfill(
        sensorHistoryStore, bodyTemperatureEstimator, acknowledgingHealthRecordWriter, SensorHistoryModule.clock,
    )

    val sleepNightReplacement = SleepNightReplacement(
        client = HealthConnectClient.getOrCreate(context),
        eraser = sensorHistoryStore,
        ingest = sensorDataSyncWorker,
    )

    val legacyRestingHeartRateCleanup = LegacyRecordCleanup(
        client = HealthConnectClient.getOrCreate(context),
        recordType = RestingHeartRateRecord::class,
        derivedState = restingHeartRateState,
    )

    /** Older builds wrote Health Services' active-only calories as total calories. */
    val legacyTotalCaloriesCleanup = LegacyRecordCleanup(
        client = HealthConnectClient.getOrCreate(context),
        recordType = TotalCaloriesBurnedRecord::class,
    )

    val backendApi: BackendApi = BackendApiFactory.create(backendBaseUrl)

    val mcpAgentSettingsStore: McpAgentSettingsStore = SharedPreferencesMcpAgentSettingsStore(context)

    /** Tools the local MCP agent exposes; register new [McpTool] implementations here. */
    val mcpTools: List<McpTool> = run {
        val arguments = HistoryToolArguments(SensorHistoryModule.clock, SensorHistoryModule.zone)
        val format = HistoryToolFormat(SensorHistoryModule.zone)
        val selector = RollupResolutionSelector(SensorHistoryModule.zone)
        val catalog = SensorMetricCatalog()
        listOf(
            ListMetricsTool(
                sensorHistoryStore, historyRetentionStore, sleepSessionRetentionStore, sensorHistoryStore, catalog,
                arguments, format,
                SensorHistoryModule.zone,
            ),
            GetSeriesTool(sensorHistoryStore, arguments, selector, format),
            GetMultiSeriesTool(sensorHistoryStore, arguments, selector, catalog, format),
            GetDailyProfileTool(sensorHistoryStore, arguments, catalog, format, SensorHistoryModule.zone),
            GetSummaryTool(sensorHistoryStore, arguments, selector, format),
            GetRawSamplesTool(sensorHistoryStore, arguments, format),
            ExportSamplesTool(sensorHistoryStore, arguments, format),
            GetSleepSessionsTool(sensorHistoryStore, sensorHistoryStore, arguments, format),
            GetExerciseRouteTool(exerciseRouteStore, arguments, format),
            GetNightlySkinTemperatureTool(nightlySkinTemperature, arguments, format),
            GetDeliveryStatusTool(sensorHistoryStore, arguments, format),
        )
    }
}
