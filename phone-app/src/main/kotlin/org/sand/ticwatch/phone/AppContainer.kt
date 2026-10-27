package org.sand.ticwatch.phone

import org.sand.ticwatch.phone.insights.HealthInsightsSource
import org.sand.ticwatch.phone.insights.HistoryHealthInsightsSource
import org.sand.ticwatch.phone.insights.ReadinessScorer
import org.sand.ticwatch.phone.insights.CircadianLightExposureCalculator
import org.sand.ticwatch.phone.insights.DailyStressCalculator
import org.sand.ticwatch.phone.insights.DailyStressHistory
import org.sand.ticwatch.phone.insights.HealthScoresSource
import org.sand.ticwatch.phone.insights.HistoryHealthScoresSource
import org.sand.ticwatch.phone.insights.HistorySleepScoreSource
import org.sand.ticwatch.phone.insights.HrvStatusClassifier
import org.sand.ticwatch.phone.insights.HrvStatusHistory
import org.sand.ticwatch.phone.insights.MorningLightHistory
import org.sand.ticwatch.phone.insights.NightlyRmssdHistory
import org.sand.ticwatch.phone.insights.SleepScoreCalculator
import org.sand.ticwatch.phone.insights.SleepScoreDeriver
import org.sand.ticwatch.phone.insights.TrainingLoadBalanceCalculator
import org.sand.ticwatch.phone.insights.TrainingLoadHistory
import org.sand.ticwatch.phone.health.CardioLoadDeriver
import org.sand.ticwatch.phone.health.ExerciseLoadCalculator
import org.sand.ticwatch.phone.datalayer.HealthScoresPublisher
import org.sand.ticwatch.phone.datalayer.WatchStatePublisher
import org.sand.ticwatch.phone.mcp.spaceweather.GetSpaceWeatherTool
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherModule
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import org.sand.ticwatch.phone.datalayer.WatchHealthScoresPublisher
import org.sand.ticwatch.phone.datalayer.BloodPressureRequestHandler
import org.sand.ticwatch.phone.datalayer.WatchBloodPressurePublisher
import org.sand.ticwatch.phone.datalayer.WatchBloodPressureRequestHandler
import org.sand.ticwatch.phone.mcp.history.GetHrvStatusTool
import org.sand.ticwatch.phone.mcp.history.GetSleepScoreTool
import org.sand.ticwatch.phone.mcp.history.GetBloodPressureTool
import org.sand.ticwatch.phone.bpm.BiolandCuffClient
import org.sand.ticwatch.phone.bpm.CuffReadingRecorder
import org.sand.ticwatch.phone.bpm.SharedPreferencesCuffStatusStore
import org.sand.ticwatch.phone.health.BloodPressureEstimator
import org.sand.ticwatch.phone.health.BloodPressureLog
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.HealthConnectBloodPressureEraser
import org.sand.ticwatch.phone.health.WristSignalsReader
import org.sand.ticwatch.phone.mcp.history.GetTrainingLoadTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import android.content.Context
import com.google.android.gms.wearable.Wearable
import org.sand.ticwatch.phone.datalayer.TransportWatchAppManager
import org.sand.ticwatch.phone.datalayer.WatchAppManager
import org.sand.ticwatch.phone.datalayer.WatchReplies
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
import org.sand.ticwatch.phone.health.ActivityIntensityDeriver
import org.sand.ticwatch.phone.health.AutoExerciseDeriver
import org.sand.ticwatch.phone.health.BasalBodyTemperatureDeriver
import org.sand.ticwatch.phone.health.NightlySkinTemperatureDeriver
import org.sand.ticwatch.phone.health.ProfileMeasurementDeriver
import org.sand.ticwatch.phone.health.RunVo2MaxDeriver
import org.sand.ticwatch.phone.health.RunningPowerDeriver
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SessionRoutePoints
import org.sand.ticwatch.phone.health.SharedPreferencesInstantLog
import org.sand.ticwatch.phone.health.SleepBreathingSummaryDeriver
import org.sand.ticwatch.phone.mcp.history.GetSleepBreathingTool
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
import org.sand.ticwatch.phone.mcp.watch.GetWatchLogsTool
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

    val healthRecordWriter: HealthRecordWriter =
        HealthConnectRecordWriter(context, exerciseRouteStore, sensorHistoryStore, sensorHistoryStore)

    private val sampleWindowReader = SampleWindowReader(sensorHistoryStore)

    private val sessionRoutePoints = SessionRoutePoints(exerciseRouteStore)

    val workoutRouteRecorder: WorkoutRouteRecorder = ServiceWorkoutRouteRecorder(context)

    private val restingHeartRateState =
        context.getSharedPreferences(SharedPreferencesDailyMinimumStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

    val sensorHistoryMaintenance: SensorHistoryMaintenance = sensorHistoryStore

    val historyRetentionStore: HistoryRetentionStore = SensorHistoryModule.retentionStore(context)

    val sleepSessionRetentionStore: SleepSessionRetentionStore = SensorHistoryModule.sleepSessionRetentionStore(context)

    private val profileStore = SharedPreferencesUserProfileStore(context)

    val userProfileStore: UserProfileStore = profileStore

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

    private val sleepScores = HistorySleepScoreSource(sampleWindowReader, sensorHistoryStore, SleepScoreCalculator())

    private val hrvStatusHistory = HrvStatusHistory(
        NightlyRmssdHistory(sensorHistoryStore, SensorHistoryModule.zone), HrvStatusClassifier(),
    )

    private val trainingLoadHistory =
        TrainingLoadHistory(sensorHistoryStore, TrainingLoadBalanceCalculator(), SensorHistoryModule.zone)

    val sensorDataSyncWorker: SensorDataSyncWorker = SensorDataSyncWorker(
        healthRecordWriter = acknowledgingHealthRecordWriter,
        derivers = listOf(
            RestingHeartRateEstimator(store = restingHeartRateMinimums),
            Vo2MaxEstimator(profile = userProfileStore),
            SpeedDeriver(),
            StepsCadenceDeriver(),
            BasalMetabolicRateDeriver(userProfileStore, MifflinStJeorFormula(), SharedPreferencesDailyValueLog(context)),
            ProfileMeasurementDeriver(
                SensorType.WEIGHT, userProfileStore::weightKg, SharedPreferencesDailyValueLog(context, WEIGHT_STATE_PREFS),
            ),
            ProfileMeasurementDeriver(
                SensorType.HEIGHT, userProfileStore::heightCm, SharedPreferencesDailyValueLog(context, HEIGHT_STATE_PREFS),
            ),
            ActivityIntensityDeriver(userProfileStore, restingHeartRateBaseline),
            AutoExerciseDeriver(sampleWindowReader, SharedPreferencesInstantLog(context, AUTO_EXERCISE_STATE_PREFS)),
            // After the auto-exercise deriver, whose detected walks/runs it also scores.
            CardioLoadDeriver(
                sampleWindowReader, userProfileStore, restingHeartRateBaseline, ExerciseLoadCalculator(),
                SharedPreferencesInstantLog(context, CARDIO_LOAD_STATE_PREFS), SensorHistoryModule.zone,
            ),
            RunningPowerDeriver(sessionRoutePoints, userProfileStore),
            RunVo2MaxDeriver(sessionRoutePoints, sampleWindowReader, userProfileStore, restingHeartRateBaseline),
            bodyTemperatureEstimator,
            // Night summaries read the night's stored samples, body temperature included, so they run last.
            NightlySkinTemperatureDeriver(sampleWindowReader, nightlySkinTemperature),
            BasalBodyTemperatureDeriver(sampleWindowReader),
            SleepBreathingSummaryDeriver(sampleWindowReader),
            SleepScoreDeriver(sleepScores),
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

    /** Dashboard "Blood Pressure" tab and the get_blood_pressure MCP tool. */
    val bloodPressureMonitor = BloodPressureMonitor(
        calibrations = profileStore,
        signals = WristSignalsReader(sampleWindowReader),
        estimator = BloodPressureEstimator(),
        log = BloodPressureLog(
            writer = acknowledgingHealthRecordWriter,
            samples = sampleWindowReader,
            historyEraser = sensorHistoryStore,
            recordEraser = HealthConnectBloodPressureEraser(HealthConnectClient.getOrCreate(context)),
            // Resolved on call: the publisher reads back through this monitor.
            listener = { bloodPressurePublisher.onLogChanged() },
        ),
        clock = SensorHistoryModule.clock,
    )

    /** Mirrors the latest saved readings to the watch's blood pressure page. */
    val bloodPressurePublisher: WatchBloodPressurePublisher by lazy {
        WatchBloodPressurePublisher(
            bloodPressureMonitor, deviceLinkTransport, SensorHistoryModule.clock, CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }

    /** Answers the watch's "Measure" button: estimate, save, reply with a status line. */
    val bloodPressureRequestHandler: BloodPressureRequestHandler =
        WatchBloodPressureRequestHandler(bloodPressureMonitor, deviceLinkTransport, SensorHistoryModule.clock)

    private val appContext = context.applicationContext

    /** Bonded Bioland cuff: each result is stored and may recalibrate [bloodPressureMonitor]; owned by a long-lived service. */
    fun bloodPressureCuffClient(scope: CoroutineScope): BiolandCuffClient =
        BiolandCuffClient(
            appContext,
            CuffReadingRecorder(bloodPressureMonitor, cuffStatusStore, scope, SensorHistoryModule.clock, SensorHistoryModule.zone),
            SensorHistoryModule.clock,
            SensorHistoryModule.zone,
        )

    /** Battery and serial the cuff last reported, shown on the Blood Pressure tab. */
    val cuffStatusStore = SharedPreferencesCuffStatusStore(appContext)

    val mcpAgentSettingsStore: McpAgentSettingsStore = SharedPreferencesMcpAgentSettingsStore(context)

    /** Dashboard "Metrics" tab: readiness, stress, sleep, barometer and light from the history. */
    val healthInsights: HealthInsightsSource = HistoryHealthInsightsSource(
        sensorHistoryStore, sensorHistoryStore, nightlySkinTemperature, ReadinessScorer(),
        SensorHistoryModule.clock, SensorHistoryModule.zone,
    )

    /** Sleep score, HRV status, training load, daily stress and morning light, for both dashboards. */
    val healthScores: HealthScoresSource = HistoryHealthScoresSource(
        queries = sensorHistoryStore,
        sleepScores = sleepScores,
        hrv = hrvStatusHistory,
        training = trainingLoadHistory,
        stress = DailyStressHistory(sensorHistoryStore, DailyStressCalculator(), SensorHistoryModule.zone),
        light = MorningLightHistory(sensorHistoryStore, CircadianLightExposureCalculator(SensorHistoryModule.zone)),
        clock = SensorHistoryModule.clock,
        zone = SensorHistoryModule.zone,
    )

    val healthScoresPublisher: HealthScoresPublisher =
        WatchHealthScoresPublisher(healthScores, deviceLinkTransport, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** NOAA Kp / solar flux the [org.sand.ticwatch.phone.spaceweather.SpaceWeatherRefreshWorker] last fetched. */
    val spaceWeatherStore: SpaceWeatherStore = SpaceWeatherModule.store(context)

    /** Phone-held state the watch mirrors; each is re-sent (deduplicated) after every stored sensor push. */
    val watchStatePublishers: List<WatchStatePublisher> by lazy {
        listOf(
            healthScoresPublisher,
            bloodPressurePublisher,
            SpaceWeatherModule.publisher(spaceWeatherStore, deviceLinkTransport),
        )
    }

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
            GetSleepBreathingTool(sensorHistoryStore, sampleWindowReader, arguments, format),
            GetSleepScoreTool(sensorHistoryStore, sleepScores, arguments, format),
            GetHrvStatusTool(hrvStatusHistory, arguments, format, SensorHistoryModule.zone),
            GetTrainingLoadTool(trainingLoadHistory, arguments, format, SensorHistoryModule.zone),
            GetBloodPressureTool(bloodPressureMonitor, arguments, format),
            GetDeliveryStatusTool(sensorHistoryStore, arguments, format),
            GetWatchLogsTool(deviceLinkTransport, WatchReplies, arguments),
            GetSpaceWeatherTool(spaceWeatherStore, SensorHistoryModule.zone),
        )
    }

    private companion object {
        const val WEIGHT_STATE_PREFS = "profile_weight_state"
        const val HEIGHT_STATE_PREFS = "profile_height_state"
        const val AUTO_EXERCISE_STATE_PREFS = "auto_exercise_state"
        const val CARDIO_LOAD_STATE_PREFS = "cardio_load_state"
    }
}
