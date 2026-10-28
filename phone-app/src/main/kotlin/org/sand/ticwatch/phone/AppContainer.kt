package org.sand.ticwatch.phone

import android.os.PowerManager
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
import org.sand.ticwatch.phone.spaceweather.GeomagneticAlertPolicy
import org.sand.ticwatch.phone.health.PatientNameStore
import org.sand.ticwatch.phone.medication.CardioAdvisor
import org.sand.ticwatch.phone.medication.MedicationEffectAnalyzer
import org.sand.ticwatch.phone.medication.MedicationEffectReader
import org.sand.ticwatch.phone.medication.CardioDatabase
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import org.sand.ticwatch.phone.medication.RoomMedicationIntakeLog
import org.sand.ticwatch.phone.medication.MedicationRegimen
import org.sand.ticwatch.phone.medication.PresetMedicationRegimen
import org.sand.ticwatch.phone.report.AmbulatoryPressureCalculator
import org.sand.ticwatch.phone.report.CalibrationAccuracyCalculator
import org.sand.ticwatch.phone.report.CardioReportCalculators
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.report.CardioReportFormatter
import org.sand.ticwatch.phone.report.CardioReportGenerator
import org.sand.ticwatch.phone.report.DayNightWindow
import org.sand.ticwatch.phone.report.HeartRateReportCalculator
import org.sand.ticwatch.phone.report.HtmlCardioReportRenderer
import org.sand.ticwatch.phone.report.MarkdownCardioReportRenderer
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import org.sand.ticwatch.phone.datalayer.WatchHealthScoresPublisher
import org.sand.ticwatch.phone.datalayer.BloodPressureRequestHandler
import org.sand.ticwatch.phone.datalayer.WatchBloodPressurePublisher
import org.sand.ticwatch.phone.datalayer.WatchBloodPressureRequestHandler
import org.sand.ticwatch.phone.mcp.history.GetHrvStatusTool
import org.sand.ticwatch.phone.mcp.history.GetCardioReadinessTool
import org.sand.ticwatch.phone.advisor.AdviceSelector
import org.sand.ticwatch.phone.advisor.AutonomicExhaustionRule
import org.sand.ticwatch.phone.advisor.CardioReadinessEngine
import org.sand.ticwatch.phone.advisor.CardioReadinessSource
import org.sand.ticwatch.phone.advisor.DiastolicPressureRule
import org.sand.ticwatch.phone.advisor.FatigueRule
import org.sand.ticwatch.phone.advisor.GeomagneticStormRule
import org.sand.ticwatch.phone.advisor.HistoryCardioReadinessSource
import org.sand.ticwatch.phone.advisor.MedicationEffectCurve
import org.sand.ticwatch.phone.advisor.MedicationInfoRule
import org.sand.ticwatch.phone.advisor.NightRecoveryRule
import org.sand.ticwatch.phone.advisor.ReadinessForecaster
import org.sand.ticwatch.phone.advisor.ReadinessSnapshotReader
import org.sand.ticwatch.phone.advisor.RestingTachycardiaRule
import org.sand.ticwatch.phone.advisor.TrainingLoadInfoRule
import org.sand.ticwatch.phone.advisor.widget.AdvisorWidgetRefresher
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
import java.io.File
import org.sand.ticwatch.phone.datalayer.FileWatchScreenshotSink
import org.sand.ticwatch.phone.datalayer.WatchScreenshotSink
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
import org.sand.ticwatch.phone.snore.MicrophoneSnoreMeter
import org.sand.ticwatch.phone.snore.PhoneSnoreMonitor
import org.sand.ticwatch.phone.snore.ServiceSnoreMonitorControl
import org.sand.ticwatch.phone.snore.SnoreMonitorControl
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
import org.sand.ticwatch.phone.mcp.watch.TriggerHealthSnapshotTool
import org.sand.ticwatch.phone.snapshot.ProcessSnapshotResults
import org.sand.ticwatch.phone.snapshot.SnapshotResultLog
import org.sand.ticwatch.phone.snapshot.SnapshotResultReceiver
import org.sand.ticwatch.phone.snapshot.StoringSnapshotResultReceiver
import org.sand.ticwatch.phone.snapshot.TransportWatchSnapshotRequester
import org.sand.ticwatch.phone.snapshot.WatchSnapshotRequester
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
import org.sand.ticwatch.phone.transport.BluetoothBondedWatchSource
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.TransportFactory
import org.sand.ticwatch.phone.watchsettings.SharedPreferencesWatchSettingsStore
import org.sand.ticwatch.phone.watchsettings.WatchSettingsStore
import org.sand.ticwatch.phone.watchsettings.WatchSettingsSync

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
        bondedWatchSource = BluetoothBondedWatchSource(context),
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

    /** Name printed on the doctor's report. */
    val patientNameStore: PatientNameStore = profileStore

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
            listener = {
                bloodPressurePublisher.onLogChanged()
                advisorWidgetRefresher.publish()
            },
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

    /** Preset antihypertensive therapy (Nedal + Noliprel Forte) and its logged intakes. */
    val medicationRegimen: MedicationRegimen = PresetMedicationRegimen()

    /** Room database for cardio data (medication intakes), separate from the hand-written sensor history; opened on first query. */
    private val cardioDatabase = CardioDatabase.open(appContext)

    val medicationIntakeLog: MedicationIntakeLog = RoomMedicationIntakeLog(cardioDatabase.medicationIntakes(), SensorHistoryModule.clock)

    /** Heart rate / blood pressure change 1, 2 and 4 h after an intake. */
    val medicationEffects = MedicationEffectReader(sampleWindowReader, bloodPressureMonitor.log, MedicationEffectAnalyzer())

    /** Advice shown when a geomagnetic alert is opened. */
    val cardioAdvisor = CardioAdvisor(medicationRegimen)

    val geomagneticAlertPolicy = GeomagneticAlertPolicy()

    val medicationTracking = MedicationTracking(medicationRegimen, medicationIntakeLog, medicationEffects)

    /** Blood Pressure tab's "Export report for doctor". */
    val cardioReportComposer: CardioReportComposer by lazy {
        val window = DayNightWindow(SensorHistoryModule.zone)
        CardioReportComposer(
            generator = CardioReportGenerator(
                samples = sampleWindowReader,
                queries = sensorHistoryStore,
                bloodPressure = bloodPressureMonitor.log,
                intakes = medicationIntakeLog,
                effects = medicationEffects,
                profile = userProfileStore,
                patientNames = patientNameStore,
                regimen = medicationRegimen,
                calculators = CardioReportCalculators(
                    AmbulatoryPressureCalculator(window),
                    HeartRateReportCalculator(window),
                    CalibrationAccuracyCalculator(),
                ),
                clock = SensorHistoryModule.clock,
                zone = SensorHistoryModule.zone,
            ),
            formatter = CardioReportFormatter(),
            html = HtmlCardioReportRenderer(),
            markdown = MarkdownCardioReportRenderer(),
        )
    }

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

    /** Battery-relevant watch features (auto blood pressure, log capture); first access reads prefs - off the main thread. */
    val watchSettingsStore: WatchSettingsStore by lazy { SharedPreferencesWatchSettingsStore.get(context) }

    val watchSettingsSync: WatchSettingsSync by lazy {
        WatchSettingsSync(watchSettingsStore, deviceLinkTransport, CoroutineScope(SupervisorJob() + Dispatchers.IO), SensorHistoryModule.clock)
    }

    /** Routes the watch's sleep start/end to the link service hosting [phoneSnoreMonitor]. */
    val snoreMonitorControl: SnoreMonitorControl = ServiceSnoreMonitorControl(appContext)

    /** Baseline Snapshots: the Metrics tab's button and the MCP tool ask the watch, its result is stored flagged. */
    val watchSnapshotRequester: WatchSnapshotRequester = TransportWatchSnapshotRequester(deviceLinkTransport, WatchReplies)
    val snapshotResults: SnapshotResultLog = ProcessSnapshotResults
    val snapshotResultReceiver: SnapshotResultReceiver by lazy {
        StoringSnapshotResultReceiver(sensorDataSyncWorker, bloodPressureRequestHandler, snapshotResults, WatchReplies)
    }

    val watchScreenshotSink: WatchScreenshotSink =
        FileWatchScreenshotSink(File(appContext.filesDir, FileWatchScreenshotSink.DIRECTORY_NAME))

    /** Snoring analysis on the phone's microphone; owned by the long-lived link service (microphone FGS type). */
    fun phoneSnoreMonitor(scope: CoroutineScope): PhoneSnoreMonitor = PhoneSnoreMonitor(
        meter = MicrophoneSnoreMeter(appContext),
        writer = acknowledgingHealthRecordWriter,
        settings = { watchSettingsStore.settings.value },
        powerManager = appContext.getSystemService(PowerManager::class.java),
        scope = scope,
        clock = SensorHistoryModule.clock,
    )

    /** Dashboard "Advisor" tab, its widget and MCP `get_cardio_readiness`: rule-based readiness + hourly forecast. */
    val cardioReadiness: CardioReadinessSource by lazy {
        val engine = CardioReadinessEngine(
            rules = listOf(
                DiastolicPressureRule(),
                RestingTachycardiaRule(),
                AutonomicExhaustionRule(),
                FatigueRule(),
                GeomagneticStormRule(),
                NightRecoveryRule(),
                TrainingLoadInfoRule(),
                MedicationInfoRule(),
            ),
            adviceSelector = AdviceSelector(),
        )
        HistoryCardioReadinessSource(
            reader = ReadinessSnapshotReader(
                queries = sensorHistoryStore,
                pressureLog = bloodPressureMonitor.log,
                pressureCalculator = AmbulatoryPressureCalculator(DayNightWindow(SensorHistoryModule.zone)),
                intakes = medicationIntakeLog,
                spaceWeather = spaceWeatherStore,
                clock = SensorHistoryModule.clock,
                zone = SensorHistoryModule.zone,
            ),
            engine = engine,
            forecaster = ReadinessForecaster(engine, MedicationEffectCurve(), SensorHistoryModule.zone),
        )
    }

    private val advisorWidgetRefresher = AdvisorWidgetRefresher(appContext)

    /**
     * Phone-held state the watch mirrors; each is re-sent (deduplicated) after every stored sensor push. The advisor
     * widget rides on the same hook to re-render after new data.
     */
    val watchStatePublishers: List<WatchStatePublisher> by lazy {
        listOf(
            advisorWidgetRefresher,
            watchSettingsSync,
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
            TriggerHealthSnapshotTool(watchSnapshotRequester, format),
            GetSpaceWeatherTool(spaceWeatherStore, SensorHistoryModule.zone),
            GetCardioReadinessTool(cardioReadiness, format),
        )
    }

    private companion object {
        const val WEIGHT_STATE_PREFS = "profile_weight_state"
        const val HEIGHT_STATE_PREFS = "profile_height_state"
        const val AUTO_EXERCISE_STATE_PREFS = "auto_exercise_state"
        const val CARDIO_LOAD_STATE_PREFS = "cardio_load_state"
    }
}
