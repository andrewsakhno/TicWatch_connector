package org.sand.ticwatch.phone

import android.app.AlarmManager
import android.content.Context
import android.content.res.Configuration
import android.os.PowerManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.time.Duration
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.sand.ticwatch.phone.advisor.AdviceSelector
import org.sand.ticwatch.phone.advisor.AutonomicExhaustionRule
import org.sand.ticwatch.phone.advisor.CardioReadinessEngine
import org.sand.ticwatch.phone.advisor.CardioReadinessPayload
import org.sand.ticwatch.phone.advisor.CardioReadinessSource
import org.sand.ticwatch.phone.advisor.DiastolicPressureRule
import org.sand.ticwatch.phone.advisor.FatigueRule
import org.sand.ticwatch.phone.advisor.GeomagneticStormRule
import org.sand.ticwatch.phone.advisor.AutonomicBalanceCalculator
import org.sand.ticwatch.phone.advisor.HeartRateDropDetector
import org.sand.ticwatch.phone.advisor.HistoryAutonomicBalanceSource
import org.sand.ticwatch.phone.advisor.HistoryCardioReadinessSource
import org.sand.ticwatch.phone.advisor.MedicationEffectCurve
import org.sand.ticwatch.phone.advisor.MedicationProtectionRule
import org.sand.ticwatch.phone.advisor.NightRecoveryRule
import org.sand.ticwatch.phone.advisor.ReadinessForecaster
import org.sand.ticwatch.phone.advisor.ReadinessSnapshotReader
import org.sand.ticwatch.phone.advisor.SystemicFatigueRule
import org.sand.ticwatch.phone.advisor.RestingTachycardiaRule
import org.sand.ticwatch.phone.advisor.TrainingLoadInfoRule
import org.sand.ticwatch.phone.advisor.widget.AdvisorWidgetRefresher
import org.sand.ticwatch.phone.bpm.BiolandCuffClient
import org.sand.ticwatch.phone.bpm.CuffReadingRecorder
import org.sand.ticwatch.phone.bpm.SharedPreferencesCuffStatusStore
import org.sand.ticwatch.phone.datalayer.BloodPressureRequestHandler
import org.sand.ticwatch.phone.datalayer.DataLayerPaths
import org.sand.ticwatch.phone.datalayer.CoalescingWatchStatePublisher
import org.sand.ticwatch.phone.datalayer.DedupingPayloadPublisher
import org.sand.ticwatch.phone.datalayer.TimestampedPayloadRefresh
import org.sand.ticwatch.phone.datalayer.FileWatchProbeDumpSink
import org.sand.ticwatch.phone.datalayer.FileWatchScreenshotSink
import org.sand.ticwatch.phone.datalayer.HealthScoresPublisher
import org.sand.ticwatch.phone.datalayer.TransportWatchAppManager
import org.sand.ticwatch.phone.datalayer.TransportWatchSyncRequester
import org.sand.ticwatch.phone.datalayer.TransportWatchWorkoutCommands
import org.sand.ticwatch.phone.datalayer.WatchWorkoutCommands
import org.sand.ticwatch.phone.datalayer.LiveWatchSync
import org.sand.ticwatch.phone.datalayer.WatchAppManager
import org.sand.ticwatch.phone.datalayer.WatchBloodPressurePublisher
import org.sand.ticwatch.phone.datalayer.WatchBloodPressureRequestHandler
import org.sand.ticwatch.phone.datalayer.WatchHealthScoresPublisher
import org.sand.ticwatch.phone.datalayer.WatchProbeDumpSink
import org.sand.ticwatch.phone.datalayer.WatchReplies
import org.sand.ticwatch.phone.datalayer.WatchScreenshotSink
import org.sand.ticwatch.phone.datalayer.WatchStatePublisher
import org.sand.ticwatch.phone.datalayer.WatchSyncRequester
import org.sand.ticwatch.phone.deviation.DeviationAnswers
import org.sand.ticwatch.phone.deviation.DeviationEpisodeLog
import org.sand.ticwatch.phone.deviation.DeviationModule
import org.sand.ticwatch.phone.datalayer.WatchHeartRateCorridorPublisher
import org.sand.ticwatch.phone.datalayer.WatchBloodPressureCalibrationPublisher
import org.sand.ticwatch.phone.deviation.DeviationScanWorker
import org.sand.ticwatch.phone.ecg.CardiogramAnalyzer
import org.sand.ticwatch.phone.ecg.CuffReadingSource
import org.sand.ticwatch.phone.ecg.EcgAnalyzer
import org.sand.ticwatch.phone.ecg.EcgMessageReceiver
import org.sand.ticwatch.phone.ecg.EcgModule
import org.sand.ticwatch.phone.ecg.EcgRecordingLog
import org.sand.ticwatch.phone.ecg.EcgRecordingPurger
import org.sand.ticwatch.phone.ecg.EcgRequester
import org.sand.ticwatch.phone.ecg.EcgRetention
import org.sand.ticwatch.phone.ecg.EcgRhythmAnalyzer
import org.sand.ticwatch.phone.ecg.EcgSessionLog
import org.sand.ticwatch.phone.ecg.ProcessEcgSessions
import org.sand.ticwatch.phone.ecg.PulseAwareCardiogramAnalyzer
import org.sand.ticwatch.phone.ecg.PulseBloodPressureCalibrator
import org.sand.ticwatch.phone.ecg.PulseBloodPressureEstimator
import org.sand.ticwatch.phone.ecg.RawPulseWaveAnalyzer
import org.sand.ticwatch.phone.ecg.RidgePulseBloodPressureEstimator
import org.sand.ticwatch.phone.ecg.RoomEcgRecordingLog
import org.sand.ticwatch.phone.ecg.StoringEcgMessageReceiver
import org.sand.ticwatch.phone.ecg.NightPulseBloodPressureRecorder
import org.sand.ticwatch.phone.ecg.TransportEcgRequester
import org.sand.ticwatch.phone.health.ActivityIntensityDeriver
import org.sand.ticwatch.phone.health.AutoExerciseDeriver
import org.sand.ticwatch.phone.health.BasalBodyTemperatureDeriver
import org.sand.ticwatch.phone.health.BasalMetabolicRateDeriver
import org.sand.ticwatch.phone.health.BloodPressureEstimator
import org.sand.ticwatch.phone.health.BloodPressureLog
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.CardioLoadDeriver
import org.sand.ticwatch.phone.health.CircadianOffset
import org.sand.ticwatch.phone.health.CorrelatedBodyTemperatureEstimator
import org.sand.ticwatch.phone.health.DualHeatFluxCoreModel
import org.sand.ticwatch.phone.health.ExerciseLoadCalculator
import org.sand.ticwatch.phone.health.ExerciseRecoveryQualifier
import org.sand.ticwatch.phone.health.HealthConnectBloodPressureEraser
import org.sand.ticwatch.phone.health.HealthConnectRecordWriter
import org.sand.ticwatch.phone.health.HealthRecordWriter
import org.sand.ticwatch.phone.health.HeartRateElevationQualifier
import org.sand.ticwatch.phone.health.LegacyRecordCleanup
import org.sand.ticwatch.phone.health.MifflinStJeorFormula
import org.sand.ticwatch.phone.health.NightlySkinTemperatureDeriver
import org.sand.ticwatch.phone.health.OrphanSleepSessionDeriver
import org.sand.ticwatch.phone.health.PatientNameStore
import org.sand.ticwatch.phone.health.ProfileMeasurementDeriver
import org.sand.ticwatch.phone.health.QualifiedCoreModel
import org.sand.ticwatch.phone.health.RestingHeartRateBaseline
import org.sand.ticwatch.phone.health.RestingHeartRateEstimator
import org.sand.ticwatch.phone.health.RunVo2MaxDeriver
import org.sand.ticwatch.phone.health.RunningPowerDeriver
import org.sand.ticwatch.phone.health.SampleWindowReader
import org.sand.ticwatch.phone.health.SensorDataSyncWorker
import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.health.SessionRoutePoints
import org.sand.ticwatch.phone.health.SharedPreferencesDailyMinimumStore
import org.sand.ticwatch.phone.health.SharedPreferencesDailyValueLog
import org.sand.ticwatch.phone.health.FatigueIndexDeriver
import org.sand.ticwatch.phone.health.SharedPreferencesInstantLog
import org.sand.ticwatch.phone.health.SharedPreferencesUserProfileStore
import org.sand.ticwatch.phone.health.SkinOffsetCoreModel
import org.sand.ticwatch.phone.health.SleepBreathingSummaryDeriver
import org.sand.ticwatch.phone.health.SleepNightReplacement
import org.sand.ticwatch.phone.health.SpeedDeriver
import org.sand.ticwatch.phone.health.StepCadenceQualifier
import org.sand.ticwatch.phone.health.StepsCadenceDeriver
import org.sand.ticwatch.phone.health.UserProfileStore
import org.sand.ticwatch.phone.health.Vo2MaxEstimator
import org.sand.ticwatch.phone.health.WristSignalsReader
import org.sand.ticwatch.phone.health.WristCalibrationRestCheck
import org.sand.ticwatch.phone.health.RestingHeartRateFloor
import org.sand.ticwatch.phone.health.SystolicOffsetSource
import org.sand.ticwatch.phone.history.AcknowledgingHealthRecordWriter
import org.sand.ticwatch.phone.history.BodyTemperatureBackfill
import org.sand.ticwatch.phone.history.HistoryRetentionStore
import org.sand.ticwatch.phone.history.NightlySkinTemperature
import org.sand.ticwatch.phone.history.SensorHistoryMaintenance
import org.sand.ticwatch.phone.history.SensorHistoryQueries
import org.sand.ticwatch.phone.cloud.BackgroundCloudHistory
import org.sand.ticwatch.phone.cloud.CloudBackedSensorHistoryQueries
import org.sand.ticwatch.phone.cloud.CloudModule
import org.sand.ticwatch.phone.cloud.CloudSettingsStore
import org.sand.ticwatch.phone.cloud.CloudSyncScheduler
import org.sand.ticwatch.phone.cloud.CloudSyncStatusStore
import org.sand.ticwatch.phone.cloud.CloudSyncTrigger
import org.sand.ticwatch.phone.cloud.WorkManagerCloudSyncScheduler
import org.sand.ticwatch.phone.ecg.EcgRecordingStoredListener
import org.sand.ticwatch.phone.history.SensorHistoryModule
import org.sand.ticwatch.phone.history.SensorHistoryPurger
import org.sand.ticwatch.phone.history.SettingStore
import org.sand.ticwatch.phone.history.SleepSessionRetentionStore
import org.sand.ticwatch.phone.insights.CircadianLightExposureCalculator
import org.sand.ticwatch.phone.insights.DailyStressCalculator
import org.sand.ticwatch.phone.insights.DailyStressHistory
import org.sand.ticwatch.phone.insights.HealthInsightsSource
import org.sand.ticwatch.phone.insights.CircadianNorm
import org.sand.ticwatch.phone.insights.FatigueHistory
import org.sand.ticwatch.phone.insights.FatigueIndexCalculator
import org.sand.ticwatch.phone.insights.RollupFatigueHistory
import org.sand.ticwatch.phone.insights.FatigueIndexSource
import org.sand.ticwatch.phone.insights.HealthScoresSource
import org.sand.ticwatch.phone.insights.HistoryFatigueIndexSource
import org.sand.ticwatch.phone.insights.HistoryHealthInsightsSource
import org.sand.ticwatch.phone.insights.HistoryHealthScoresSource
import org.sand.ticwatch.phone.insights.HistorySleepScoreSource
import org.sand.ticwatch.phone.insights.HrvStatusClassifier
import org.sand.ticwatch.phone.insights.HrvStatusHistory
import org.sand.ticwatch.phone.insights.MorningLightHistory
import org.sand.ticwatch.phone.insights.NightlyRmssdHistory
import org.sand.ticwatch.phone.insights.ReadinessScorer
import org.sand.ticwatch.phone.insights.SleepScoreCalculator
import org.sand.ticwatch.phone.insights.SleepScoreDeriver
import org.sand.ticwatch.phone.insights.TrainingLoadBalanceCalculator
import org.sand.ticwatch.phone.insights.TrainingLoadHistory
import org.sand.ticwatch.phone.insights.WorkoutJournal
import org.sand.ticwatch.phone.insights.HistoryWorkoutJournal
import org.sand.ticwatch.phone.language.AppCompatLocaleSelector
import org.sand.ticwatch.phone.language.AppLanguageSettings
import org.sand.ticwatch.phone.mcp.McpAgentSettingsStore
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.mcp.SharedPreferencesMcpAgentSettingsStore
import org.sand.ticwatch.phone.mcp.history.ExportSamplesTool
import org.sand.ticwatch.phone.mcp.history.GetBloodPressureTool
import org.sand.ticwatch.phone.mcp.history.GetCardioReadinessTool
import org.sand.ticwatch.phone.mcp.history.GetDailyProfileTool
import org.sand.ticwatch.phone.mcp.history.GetDeliveryStatusTool
import org.sand.ticwatch.phone.mcp.history.GetExerciseRouteTool
import org.sand.ticwatch.phone.mcp.history.GetHrvStatusTool
import org.sand.ticwatch.phone.mcp.history.GetMultiSeriesTool
import org.sand.ticwatch.phone.mcp.history.GetNightlySkinTemperatureTool
import org.sand.ticwatch.phone.mcp.history.GetRawSamplesTool
import org.sand.ticwatch.phone.mcp.history.GetSeriesTool
import org.sand.ticwatch.phone.mcp.history.GetSleepAdvancedInsightsTool
import org.sand.ticwatch.phone.mcp.history.GetSleepBreathingTool
import org.sand.ticwatch.phone.mcp.history.GetSleepScoreTool
import org.sand.ticwatch.phone.sleep.LocalSleepJournal
import org.sand.ticwatch.phone.sleep.SleepJournal
import org.sand.ticwatch.phone.mcp.history.GetSleepSessionsTool
import org.sand.ticwatch.phone.mcp.history.GetSummaryTool
import org.sand.ticwatch.phone.mcp.history.GetTrainingLoadTool
import org.sand.ticwatch.phone.mcp.history.HistoryToolArguments
import org.sand.ticwatch.phone.mcp.history.HistoryToolFormat
import org.sand.ticwatch.phone.mcp.history.ListMetricsTool
import org.sand.ticwatch.phone.mcp.history.RollupResolutionSelector
import org.sand.ticwatch.phone.mcp.history.SensorMetricCatalog
import org.sand.ticwatch.phone.mcp.spaceweather.GetSpaceWeatherTool
import org.sand.ticwatch.phone.mcp.watch.GetWatchLogsTool
import org.sand.ticwatch.phone.mcp.watch.EndWorkoutTool
import org.sand.ticwatch.phone.mcp.watch.StartWorkoutTool
import org.sand.ticwatch.phone.mcp.watch.TriggerHealthSnapshotTool
import org.sand.ticwatch.phone.medication.CardioAdvisor
import org.sand.ticwatch.phone.medication.CardioDatabase
import org.sand.ticwatch.phone.medication.MedicationEffectAnalyzer
import org.sand.ticwatch.phone.medication.MedicationEffectReader
import org.sand.ticwatch.phone.medication.MedicationIntakeLog
import org.sand.ticwatch.phone.medication.MedicationProtectionReader
import org.sand.ticwatch.phone.medication.MedicationProtectionTracker
import org.sand.ticwatch.phone.medication.RoomMedicationIntakeLog
import org.sand.ticwatch.phone.report.AmbulatoryPressureCalculator
import org.sand.ticwatch.phone.report.CalibrationAccuracyCalculator
import org.sand.ticwatch.phone.report.CuffBiasSystolicOffset
import org.sand.ticwatch.phone.report.CardioReportCalculators
import org.sand.ticwatch.phone.report.CardioReportComposer
import org.sand.ticwatch.phone.report.CardioReportFormatter
import org.sand.ticwatch.phone.report.CardioReportGenerator
import org.sand.ticwatch.phone.report.DayNightWindow
import org.sand.ticwatch.phone.report.HeartRateReportCalculator
import org.sand.ticwatch.phone.report.HtmlCardioReportRenderer
import org.sand.ticwatch.phone.report.MarkdownCardioReportRenderer
import org.sand.ticwatch.phone.report.SleepReportCalculator
import org.sand.ticwatch.phone.route.ExerciseRouteLog
import org.sand.ticwatch.phone.route.ServiceWorkoutRouteRecorder
import org.sand.ticwatch.phone.route.SharedPreferencesWatchWorkoutActivityStore
import org.sand.ticwatch.phone.route.StateTrackingWorkoutRouteRecorder
import org.sand.ticwatch.phone.route.WatchWorkoutActivityStore
import org.sand.ticwatch.phone.route.WorkoutRouteRecorder
import org.sand.ticwatch.phone.sleep.ApneaRiskClassifier
import org.sand.ticwatch.phone.sleep.EveningVasodilationDetector
import org.sand.ticwatch.phone.sleep.SleepAdvancedAnalytics
import org.sand.ticwatch.phone.sleep.SettledNightInsightsCache
import org.sand.ticwatch.phone.sleep.SleepAdvancedInsightsReader
import org.sand.ticwatch.phone.sleep.SleepNightInsightsSource
import org.sand.ticwatch.phone.sleep.SleepContinuityCalculator
import org.sand.ticwatch.phone.sleep.SnoreCardiacArousalAnalyzer
import org.sand.ticwatch.phone.sleep.VagalRecoveryCalculator
import org.sand.ticwatch.phone.sleep.VagalRecoverySource
import org.sand.ticwatch.phone.snapshot.ProcessSnapshotResults
import org.sand.ticwatch.phone.snapshot.SnapshotResultLog
import org.sand.ticwatch.phone.snapshot.SnapshotResultReceiver
import org.sand.ticwatch.phone.snapshot.StoringSnapshotResultReceiver
import org.sand.ticwatch.phone.snapshot.TransportWatchSnapshotRequester
import org.sand.ticwatch.phone.snapshot.WatchSnapshotRequester
import org.sand.ticwatch.phone.snore.AlarmClockWakeSignal
import org.sand.ticwatch.phone.snore.AppOpenedWakeSignal
import org.sand.ticwatch.phone.snore.MicrophoneSnoreMeter
import org.sand.ticwatch.phone.snore.PhoneSnoreMonitor
import org.sand.ticwatch.phone.snore.ProcessAppActivity
import org.sand.ticwatch.phone.snore.ServiceSnoreMonitorControl
import org.sand.ticwatch.phone.snore.SnoreMonitorControl
import org.sand.ticwatch.phone.snore.WatchStepsWakeSignal
import org.sand.ticwatch.phone.spaceweather.GeomagneticAlertPolicy
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherModule
import org.sand.ticwatch.phone.spaceweather.SpaceWeatherStore
import org.sand.ticwatch.phone.text.ResourcesTextResolver
import org.sand.ticwatch.phone.text.TextResolver
import org.sand.ticwatch.phone.transport.BluetoothBondedWatchSource
import org.sand.ticwatch.phone.transport.DeviceLinkTransport
import org.sand.ticwatch.phone.transport.TransportFactory
import org.sand.ticwatch.phone.ui.DataChangeSignal
import org.sand.ticwatch.phone.watchbattery.SharedPreferencesWatchBatteryStore
import org.sand.ticwatch.phone.watchbattery.StoringWatchBatteryReceiver
import org.sand.ticwatch.phone.watchbattery.WatchBatteryReceiver
import org.sand.ticwatch.phone.watchbattery.WatchBatteryStore
import org.sand.ticwatch.phone.watchsettings.SharedPreferencesWatchSettingsStore
import org.sand.ticwatch.phone.watchsettings.WatchSettingsStore
import org.sand.ticwatch.phone.watchsettings.WatchSettingsSync

/**
 * Minimal composition root: builds concrete implementations once and hands them out
 * as their interfaces (DIP), so Activities/ViewModels take collaborators through
 * their constructor instead of instantiating framework clients themselves.
 */
class AppContainer(context: Context) {
    val deviceLinkTransport: DeviceLinkTransport = TransportFactory.create(context)

    val watchAppManager: WatchAppManager = TransportWatchAppManager(
        nodeClient = Wearable.getNodeClient(context),
        transport = deviceLinkTransport,
        bondedWatchSource = BluetoothBondedWatchSource(context),
    )

    private val exerciseRouteStore = SensorHistoryModule.routeStore(context)

    val exerciseRouteLog: ExerciseRouteLog = exerciseRouteStore

    private val sensorHistoryStore = SensorHistoryModule.store(context)

    /** Fired when older history a screen asked for has arrived from the cloud server; screens re-read on it. */
    val cloudHistoryArrivals = DataChangeSignal()

    /**
     * Read side of the history for every screen, deriver and MCP tool: ranges older than the local raw /
     * rollup retention are read back from the cloud server, so charts look as if the phone kept everything.
     */
    private val historyQueries: SensorHistoryQueries = CloudModule.historyReader(context).let { reader ->
        CloudBackedSensorHistoryQueries(
            sensorHistoryStore, sensorHistoryStore, reader,
            BackgroundCloudHistory(
                reader, CoroutineScope(SupervisorJob() + Dispatchers.IO), SensorHistoryModule.zone, cloudHistoryArrivals::notifyChanged,
            ),
            SensorHistoryModule.clock, SensorHistoryModule.zone,
        )
    }

    /** Cloud storage: settings, the title bar's status, and a round queued after each new piece of watch data. */
    val cloudSettingsStore: CloudSettingsStore by lazy { CloudModule.settings(context) }
    val cloudSyncStatusStore: CloudSyncStatusStore by lazy { CloudModule.status(context) }
    val cloudSyncScheduler: CloudSyncScheduler = WorkManagerCloudSyncScheduler(context.applicationContext)
    private val cloudSyncTrigger: WatchStatePublisher by lazy { CloudSyncTrigger(cloudSyncStatusStore, cloudSyncScheduler) }

    val healthRecordWriter: HealthRecordWriter =
        HealthConnectRecordWriter(context, exerciseRouteStore, sensorHistoryStore, sensorHistoryStore)

    private val sampleWindowReader = SampleWindowReader(historyQueries)

    private val sessionRoutePoints = SessionRoutePoints(exerciseRouteStore)

    /** The watch's workout state as last reported; the Metrics card shows it. */
    val watchWorkoutActivity: WatchWorkoutActivityStore by lazy { SharedPreferencesWatchWorkoutActivityStore.get(context) }
    val workoutRouteRecorder: WorkoutRouteRecorder by lazy {
        StateTrackingWorkoutRouteRecorder(ServiceWorkoutRouteRecorder(context), watchWorkoutActivity)
    }

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
            CloudModule.retentionHold(context), SensorHistoryModule.clock, SensorHistoryModule.zone,
        )

    private val restingHeartRateMinimums = SharedPreferencesDailyMinimumStore(restingHeartRateState)

    private val acknowledgingHealthRecordWriter: HealthRecordWriter = AcknowledgingHealthRecordWriter(
        delegate = healthRecordWriter,
        historyLog = sensorHistoryStore,
        delivery = SensorHistoryModule.delivery(healthRecordWriter, sensorHistoryStore),
        clock = SensorHistoryModule.clock,
    )

    private val restingHeartRateBaseline = RestingHeartRateBaseline(restingHeartRateMinimums, SensorHistoryModule.zone)

    private val nightlySkinTemperature = NightlySkinTemperature(historyQueries, SensorHistoryModule.clock)

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

    private val sleepScores = HistorySleepScoreSource(sampleWindowReader, historyQueries, SleepScoreCalculator())

    private val hrvStatusHistory = HrvStatusHistory(
        NightlyRmssdHistory(historyQueries, SensorHistoryModule.zone), HrvStatusClassifier(),
    )

    private val trainingLoadHistory =
        TrainingLoadHistory(historyQueries, TrainingLoadBalanceCalculator(), SensorHistoryModule.zone)

    private val sleepAdvancedAnalytics = SleepAdvancedAnalytics(
        SleepContinuityCalculator(),
        SnoreCardiacArousalAnalyzer(),
        VagalRecoveryCalculator(),
        EveningVasodilationDetector(),
        ApneaRiskClassifier(),
    )

    /** WASO, fragmentation, vagal recovery, snore-cardiac arousal per night: report, Advisor badge, fatigue, MCP, Metrics card. */
    private val sleepAdvancedInsights: SleepAdvancedInsightsReader by lazy {
        SleepAdvancedInsightsReader(
            queries = historyQueries,
            stages = sensorHistoryStore,
            samples = sampleWindowReader,
            analytics = sleepAdvancedAnalytics,
            clock = SensorHistoryModule.clock,
            zone = SensorHistoryModule.zone,
        )
    }

    /** The same analytics over the local store only, for the sleep journal. */
    private val localSleepInsights: SleepNightInsightsSource = SettledNightInsightsCache(
        SleepAdvancedInsightsReader(
            queries = sensorHistoryStore,
            stages = sensorHistoryStore,
            samples = SampleWindowReader(sensorHistoryStore),
            analytics = sleepAdvancedAnalytics,
            clock = SensorHistoryModule.clock,
            zone = SensorHistoryModule.zone,
        ),
        SensorHistoryModule.clock,
    )

    private val circadianNorm = CircadianNorm(historyQueries, SensorHistoryModule.zone)

    /** Stored by [FatigueIndexDeriver], shown on both dashboards through [healthScores]. */
    private val fatigueIndex: FatigueIndexSource = HistoryFatigueIndexSource(
        historyQueries, circadianNorm, VagalRecoverySource { sleepAdvancedInsights.lastNightRecovery() }, FatigueIndexCalculator(),
        SensorHistoryModule.clock, SensorHistoryModule.zone,
    )

    val fatigueHistory: FatigueHistory = RollupFatigueHistory(historyQueries, SensorHistoryModule.clock, SensorHistoryModule.zone)

    /** Deviations screen: episodes and the user's explanations of them, which decide what may feed calibration. */
    val deviationEpisodeLog: DeviationEpisodeLog = DeviationModule.log(context)

    val deviationAnswers = DeviationAnswers(deviationEpisodeLog, DeviationModule.asker(context), SensorHistoryModule.clock) { cloudSyncTrigger.publish() }

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
            // Before the night summaries, which then also run on a restored night.
            OrphanSleepSessionDeriver(
                stages = sensorHistoryStore,
                sessions = { from, to -> sensorHistoryStore.sleepSessions(from, to, null, MAX_ORPHAN_CHECK_SESSIONS) },
                clock = SensorHistoryModule.clock,
            ),
            // Night summaries read the night's stored samples, body temperature included, so they run last.
            NightlySkinTemperatureDeriver(sampleWindowReader, nightlySkinTemperature),
            BasalBodyTemperatureDeriver(sampleWindowReader),
            SleepBreathingSummaryDeriver(sampleWindowReader),
            SleepScoreDeriver(sleepScores),
            // After the sleep score and cardio load, whose results for this push it reads.
            FatigueIndexDeriver(fatigueIndex, SharedPreferencesInstantLog(context, FATIGUE_INDEX_STATE_PREFS), SensorHistoryModule.clock),
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

    /** Fired after every stored sensor push; screens re-read the history on it. */
    val historyChanges = DataChangeSignal()

    /** Fired after a blood pressure reading was saved or deleted (dashboard, watch, cuff). */
    val bloodPressureChanges = DataChangeSignal()

    private val bloodPressureLog = BloodPressureLog(
        writer = acknowledgingHealthRecordWriter,
        samples = sampleWindowReader,
        historyEraser = sensorHistoryStore,
        recordEraser = HealthConnectBloodPressureEraser(HealthConnectClient.getOrCreate(context)),
        // Resolved on call: the publisher reads back through this monitor.
        listener = {
            bloodPressurePublisher.onLogChanged()
            advisorWidgetRefresher.publish()
            cloudSyncTrigger.publish()
            cardioReadinessPublisher.publish()
            bloodPressureChanges.notifyChanged()
        },
    )

    /** Dashboard "Blood Pressure" tab and the get_blood_pressure MCP tool. */
    private val wristSignals = WristSignalsReader(sampleWindowReader)

    private val bloodPressureSystolicOffset: SystolicOffsetSource =
        CuffBiasSystolicOffset(bloodPressureLog, CalibrationAccuracyCalculator(), profileStore)

    /** The lower edge of both personal resting corridors: a pulse at or above it is ordinary rest. */
    private val restingHeartRateFloor = RestingHeartRateFloor { at ->
        DeviationModule.corridors(context).corridors(at).let { minOf(it.awakeRest.lowBpm, it.sleep.lowBpm) }
    }

    val bloodPressureMonitor = BloodPressureMonitor(
        calibrations = profileStore,
        signals = wristSignals,
        estimator = BloodPressureEstimator(),
        systolicOffset = bloodPressureSystolicOffset,
        restCheck = WristCalibrationRestCheck(wristSignals),
        restingFloor = restingHeartRateFloor,
        log = bloodPressureLog,
        clock = SensorHistoryModule.clock,
    )

    /** Texts in the app language (the platform applies the per-app language to the application context on API 33+). */
    val appTextResolver: TextResolver = ResourcesTextResolver { context.applicationContext.resources }

    /** Mirrors the latest saved readings to the watch's blood pressure page. */
    val bloodPressurePublisher: WatchBloodPressurePublisher by lazy {
        WatchBloodPressurePublisher(
            bloodPressureMonitor, deviceLinkTransport, SensorHistoryModule.clock, CoroutineScope(SupervisorJob() + Dispatchers.IO),
            appTextResolver,
        )
    }

    /** Answers the watch's "Measure" button: estimate, save, reply with a status line. */
    val bloodPressureRequestHandler: BloodPressureRequestHandler =
        WatchBloodPressureRequestHandler(bloodPressureMonitor, deviceLinkTransport, SensorHistoryModule.clock, appTextResolver)

    private val appContext = context.applicationContext

    /** Bonded Bioland cuff: each result is stored and may recalibrate [bloodPressureMonitor]; owned by a long-lived service. */
    fun bloodPressureCuffClient(scope: CoroutineScope): BiolandCuffClient =
        BiolandCuffClient(
            appContext,
            CuffReadingRecorder(
                bloodPressureMonitor,
                cuffStatusStore,
                watchSyncRequester,
                // A strip right after each live cuff reading pairs it for the pulse-wave blood pressure model; the watch asks first.
                followUp = { ecgSessions.start(promptedEcgRequester, EcgStore.PROMPTED_RECORDING_TIMEOUT) },
                scope,
                SensorHistoryModule.clock,
                SensorHistoryModule.zone,
            ),
            SensorHistoryModule.clock,
            SensorHistoryModule.zone,
        )

    /** Room database for cardio data (medication intakes), separate from the hand-written sensor history; opened on first query. */
    private val cardioDatabase = CardioDatabase.getInstance(appContext)

    /** Cardiograms (watch rhythm strips) kept on the phone; purged by [ecgRecordingPurger]. */
    val ecgRecordingLog: EcgRecordingLog = EcgModule.log(appContext)

    val ecgRetentionStore: SettingStore<EcgRetention> = EcgModule.retentionStore(context)

    val ecgRecordingPurger = EcgRecordingPurger(
        ecgRecordingLog, ecgRetentionStore, CloudModule.retentionHold(context), SensorHistoryModule.clock,
    )

    val ecgAnalyzer: EcgAnalyzer = EcgRhythmAnalyzer()

    val medicationIntakeLog: MedicationIntakeLog = RoomMedicationIntakeLog(cardioDatabase.medicationIntakes(), SensorHistoryModule.clock)

    /** Heart rate / blood pressure change 1, 2 and 4 h after an intake. */
    val medicationEffects = MedicationEffectReader(sampleWindowReader, bloodPressureMonitor.log, MedicationEffectAnalyzer())

    /** Advice shown when a geomagnetic alert is opened. */
    val cardioAdvisor = CardioAdvisor()

    val geomagneticAlertPolicy = GeomagneticAlertPolicy()

    private val medicationProtectionTracker = MedicationProtectionTracker()

    val medicationTracking = MedicationTracking(
        medicationIntakeLog,
        medicationEffects,
        MedicationProtectionReader(medicationIntakeLog, medicationProtectionTracker, SensorHistoryModule.clock),
    )

    /** Blood Pressure tab's "Export report for doctor". */
    val cardioReportComposer: CardioReportComposer by lazy {
        val window = DayNightWindow(SensorHistoryModule.zone)
        CardioReportComposer(
            generator = CardioReportGenerator(
                samples = sampleWindowReader,
                queries = historyQueries,
                bloodPressure = bloodPressureMonitor.log,
                intakes = medicationIntakeLog,
                effects = medicationEffects,
                profile = userProfileStore,
                patientNames = patientNameStore,
                sleepInsights = sleepAdvancedInsights,
                calculators = CardioReportCalculators(
                    AmbulatoryPressureCalculator(window),
                    HeartRateReportCalculator(window),
                    CalibrationAccuracyCalculator(),
                    SleepReportCalculator(),
                ),
                clock = SensorHistoryModule.clock,
                zone = SensorHistoryModule.zone,
            ),
            formatter = CardioReportFormatter(appTextResolver),
            html = HtmlCardioReportRenderer(),
            markdown = MarkdownCardioReportRenderer(),
        )
    }

    /** Battery and serial the cuff last reported, shown on the Blood Pressure tab. */
    val cuffStatusStore = SharedPreferencesCuffStatusStore(appContext)

    val mcpAgentSettingsStore: McpAgentSettingsStore = SharedPreferencesMcpAgentSettingsStore(context)

    /** Dashboard "Metrics" tab: readiness, stress, sleep, barometer and light from the history. */
    val healthInsights: HealthInsightsSource = HistoryHealthInsightsSource(
        historyQueries, sensorHistoryStore, nightlySkinTemperature, sleepAdvancedInsights, ReadinessScorer(),
        SensorHistoryModule.clock, SensorHistoryModule.zone,
    )

    /** Sleep journal behind the "Sleep score" card; local store only, it sends nothing to the cloud server. */
    val sleepJournal: SleepJournal = LocalSleepJournal(sensorHistoryStore, sensorHistoryStore, localSleepInsights, SensorHistoryModule.zone)

    /** Workout list behind the "Training load" card. */
    val workoutJournal: WorkoutJournal =
        HistoryWorkoutJournal(sampleWindowReader, sessionRoutePoints, SensorHistoryModule.clock, SensorHistoryModule.zone)

    /** Sleep score, HRV status, training load, daily stress and morning light, for both dashboards. */
    val healthScores: HealthScoresSource = HistoryHealthScoresSource(
        queries = historyQueries,
        sleepScores = sleepScores,
        hrv = hrvStatusHistory,
        training = trainingLoadHistory,
        stress = DailyStressHistory(historyQueries, DailyStressCalculator(), SensorHistoryModule.zone),
        light = MorningLightHistory(historyQueries, CircadianLightExposureCalculator(SensorHistoryModule.zone)),
        fatigue = fatigueIndex,
        clock = SensorHistoryModule.clock,
        zone = SensorHistoryModule.zone,
    )

    val healthScoresPublisher: HealthScoresPublisher =
        WatchHealthScoresPublisher(healthScores, deviceLinkTransport, CoroutineScope(SupervisorJob() + Dispatchers.IO), appTextResolver)

    /** NOAA Kp / solar flux the [org.sand.ticwatch.phone.spaceweather.SpaceWeatherRefreshWorker] last fetched. */
    val spaceWeatherStore: SpaceWeatherStore = SpaceWeatherModule.store(context)

    /** Battery-relevant watch features (auto blood pressure, log capture); first access reads prefs - off the main thread. */
    val watchSettingsStore: WatchSettingsStore by lazy { SharedPreferencesWatchSettingsStore.get(context) }

    val watchSettingsSync: WatchSettingsSync by lazy {
        WatchSettingsSync(watchSettingsStore, deviceLinkTransport, CoroutineScope(SupervisorJob() + Dispatchers.IO), SensorHistoryModule.clock)
    }

    /** App language (phone and, through [watchSettingsSync], the watch). */
    val appLanguageSettings: AppLanguageSettings by lazy {
        AppLanguageSettings(AppCompatLocaleSelector(), watchSettingsSync, Dispatchers.IO)
    }

    /** Watch battery level, sent with every watch sync flush; shown in the title bar and the advisor widget. */
    val watchBatteryStore: WatchBatteryStore by lazy { SharedPreferencesWatchBatteryStore.get(context) }

    val watchBatteryReceiver: WatchBatteryReceiver by lazy { StoringWatchBatteryReceiver(watchBatteryStore, advisorWidgetRefresher) }

    /** Routes the watch's sleep start/end to the link service hosting [phoneSnoreMonitor]. */
    val snoreMonitorControl: SnoreMonitorControl = ServiceSnoreMonitorControl(appContext)

    /** Pulls the watch's buffered samples on demand (before a blood pressure estimate) instead of the next sync window. */
    val watchSyncRequester: WatchSyncRequester = TransportWatchSyncRequester(deviceLinkTransport, WatchReplies)

    /** Live sync while the app is on screen: the watch flushes every few seconds instead of every 30 min. */
    val liveWatchSync = LiveWatchSync(deviceLinkTransport, TransportFactory.linkHold, TransportFactory.bulkTransferActive, Dispatchers.IO)

    /** Baseline Snapshots: the Metrics tab's button and the MCP tool ask the watch, its result is stored flagged. */
    val watchSnapshotRequester: WatchSnapshotRequester = TransportWatchSnapshotRequester(deviceLinkTransport, WatchReplies)
    val snapshotResults: SnapshotResultLog = ProcessSnapshotResults

    /** Starts/ends the watch's workout: the Metrics tab's card and the MCP tools. */
    val watchWorkoutCommands: WatchWorkoutCommands = TransportWatchWorkoutCommands(
        deviceLinkTransport, WatchReplies, CoroutineScope(SupervisorJob() + Dispatchers.IO), WATCH_WORKOUT_REPLY_TIMEOUT,
    )

    val ecgRequester: EcgRequester = TransportEcgRequester(deviceLinkTransport, WatchReplies)
    private val promptedEcgRequester: EcgRequester = TransportEcgRequester(deviceLinkTransport, WatchReplies, DataLayerPaths.MESSAGE_ECG_PROMPT)
    val ecgSessions: EcgSessionLog = ProcessEcgSessions
    val ecgMessageReceiver: EcgMessageReceiver by lazy {
        StoringEcgMessageReceiver(
            ecgRecordingLog,
            ecgSessions,
            WatchReplies,
            listOf(
                pulseBloodPressureCalibrator,
                NightPulseBloodPressureRecorder(cardiogramAnalyzer, pulseBloodPressureEstimator, acknowledgingHealthRecordWriter),
                EcgRecordingStoredListener { cloudSyncTrigger.publish() },
            ),
        )
    }

    /** Rhythm refined by the raw pulse wave: perfusion, breathing, pulse shape, premature beats checked by their pulse. */
    val cardiogramAnalyzer: CardiogramAnalyzer = PulseAwareCardiogramAnalyzer(ecgAnalyzer, RawPulseWaveAnalyzer())

    /** Personal pulse-wave blood pressure: trained on cuff readings paired with a strip recorded around them. */
    private val pulseBloodPressurePairs = cardioDatabase.pulseBloodPressurePairs()
    val pulseBloodPressureEstimator: PulseBloodPressureEstimator = RidgePulseBloodPressureEstimator(pulseBloodPressurePairs)
    private val pulseBloodPressureCalibrator = PulseBloodPressureCalibrator(
        pulseBloodPressurePairs,
        CuffReadingSource.of(bloodPressureMonitor.log::readings),
        cardiogramAnalyzer,
    )

    /** Blood Pressure / ECG tab's ECG card. */
    val ecgDependencies = EcgDependencies(ecgRecordingLog, cardiogramAnalyzer, pulseBloodPressureEstimator, ecgSessions, ecgRequester)
    val snapshotResultReceiver: SnapshotResultReceiver by lazy {
        StoringSnapshotResultReceiver(sensorDataSyncWorker, bloodPressureRequestHandler, snapshotResults, WatchReplies)
    }

    val watchScreenshotSink: WatchScreenshotSink =
        FileWatchScreenshotSink(File(appContext.filesDir, FileWatchScreenshotSink.DIRECTORY_NAME))
    val watchProbeDumpSink: WatchProbeDumpSink =
        FileWatchProbeDumpSink(File(appContext.filesDir, FileWatchProbeDumpSink.DIRECTORY_NAME))

    /** Snoring analysis on the phone's microphone; owned by the long-lived link service (microphone FGS type). */
    fun phoneSnoreMonitor(scope: CoroutineScope): PhoneSnoreMonitor = PhoneSnoreMonitor(
        meter = MicrophoneSnoreMeter(appContext),
        writer = acknowledgingHealthRecordWriter,
        settings = { watchSettingsStore.settings.value },
        powerManager = appContext.getSystemService(PowerManager::class.java),
        scope = scope,
        clock = SensorHistoryModule.clock,
        wakeSignals = listOf(
            AlarmClockWakeSignal(appContext.getSystemService(AlarmManager::class.java)),
            WatchStepsWakeSignal(sampleWindowReader),
            AppOpenedWakeSignal(ProcessAppActivity),
        ),
    )

    /** Dashboard "Advisor" tab, its widget and MCP `get_cardio_readiness`: rule-based readiness + hourly forecast. */
    val cardioReadiness: CardioReadinessSource by lazy {
        val engine = CardioReadinessEngine(
            rules = listOf(
                DiastolicPressureRule(),
                RestingTachycardiaRule(),
                AutonomicExhaustionRule(),
                FatigueRule(),
                SystemicFatigueRule(),
                GeomagneticStormRule(),
                NightRecoveryRule(),
                TrainingLoadInfoRule(),
                MedicationProtectionRule(medicationProtectionTracker),
            ),
            adviceSelector = AdviceSelector(),
        )
        HistoryCardioReadinessSource(
            reader = ReadinessSnapshotReader(
                queries = historyQueries,
                norm = circadianNorm,
                pressureLog = bloodPressureMonitor.log,
                pressureCalculator = AmbulatoryPressureCalculator(DayNightWindow(SensorHistoryModule.zone)),
                intakes = medicationIntakeLog,
                spaceWeather = spaceWeatherStore,
                fatigue = fatigueIndex,
                clock = SensorHistoryModule.clock,
                zone = SensorHistoryModule.zone,
            ),
            engine = engine,
            forecaster = ReadinessForecaster(engine, MedicationEffectCurve(), SensorHistoryModule.zone),
            protection = medicationProtectionTracker,
            recovery = sleepAdvancedInsights,
            autonomic = HistoryAutonomicBalanceSource(
                historyQueries, circadianNorm, DeviationModule.log(appContext), HeartRateDropDetector(), AutonomicBalanceCalculator(),
                SensorHistoryModule.zone,
            ),
        )
    }

    /** Sends the readiness score, state, advice + latest pressure/pulse to the watch when they changed. */
    private val cardioReadinessPublisher: WatchStatePublisher by lazy {
        DedupingPayloadPublisher(
            DataLayerPaths.MESSAGE_CARDIO_READINESS,
            deviceLinkTransport,
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            CARDIO_READINESS_PUBLISHER_TAG,
            TimestampedPayloadRefresh(CardioReadinessPayload::withoutTimestamp, CARDIO_READINESS_REFRESH, SensorHistoryModule.clock),
        ) { CardioReadinessPayload.encode(cardioReadiness.load(), appTextResolver) }
    }

    private val advisorWidgetRefresher = AdvisorWidgetRefresher(appContext)

    /**
     * Run after every stored sensor push. Phone-held state the watch mirrors is re-sent (deduplicated) once per burst of
     * pushes, together; the advisor widget and the screens ride on the same hook to re-render after new data.
     */
    val watchStatePublishers: List<WatchStatePublisher> by lazy {
        listOf(
            WatchStatePublisher(historyChanges::notifyChanged),
            cloudSyncTrigger,
            WatchStatePublisher { DeviationScanWorker.requestSoon(context) },
            advisorWidgetRefresher,
            CoalescingWatchStatePublisher(
                publishers = listOf(
                    watchSettingsSync,
                    healthScoresPublisher,
                    cardioReadinessPublisher,
                    bloodPressurePublisher,
                    SpaceWeatherModule.publisher(spaceWeatherStore, deviceLinkTransport, appTextResolver),
                    WatchHeartRateCorridorPublisher(
                        DeviationModule.corridors(context), SensorHistoryModule.clock, deviceLinkTransport,
                        CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    ),
                    WatchBloodPressureCalibrationPublisher(
                        profileStore, bloodPressureSystolicOffset, SensorHistoryModule.clock, deviceLinkTransport,
                        CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    ),
                ),
                linkHold = TransportFactory.linkHold,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                window = WATCH_REPLY_COALESCE_WINDOW,
            ),
        )
    }

    /** MCP answers stay in English whatever the app language, so the agent always reads the same vocabulary. */
    val mcpTextResolver: TextResolver by lazy {
        val english = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources = context.createConfigurationContext(english).resources
        ResourcesTextResolver { resources }
    }

    /** Tools the local MCP agent exposes; register new [McpTool] implementations here. */
    val mcpTools: List<McpTool> = run {
        val arguments = HistoryToolArguments(SensorHistoryModule.clock, SensorHistoryModule.zone)
        val format = HistoryToolFormat(SensorHistoryModule.zone, mcpTextResolver)
        val selector = RollupResolutionSelector(SensorHistoryModule.zone)
        val catalog = SensorMetricCatalog()
        listOf(
            ListMetricsTool(
                historyQueries, historyRetentionStore, sleepSessionRetentionStore, sensorHistoryStore, catalog,
                arguments, format,
                SensorHistoryModule.zone,
            ),
            GetSeriesTool(historyQueries, arguments, selector, format),
            GetMultiSeriesTool(historyQueries, arguments, selector, catalog, format),
            GetDailyProfileTool(historyQueries, arguments, catalog, format, SensorHistoryModule.zone),
            GetSummaryTool(historyQueries, arguments, selector, format),
            GetRawSamplesTool(historyQueries, arguments, format),
            ExportSamplesTool(historyQueries, arguments, format),
            GetSleepSessionsTool(historyQueries, sensorHistoryStore, arguments, format),
            GetExerciseRouteTool(exerciseRouteStore, arguments, format),
            GetNightlySkinTemperatureTool(nightlySkinTemperature, arguments, format),
            GetSleepBreathingTool(historyQueries, sampleWindowReader, arguments, format),
            GetSleepAdvancedInsightsTool(sleepAdvancedInsights, format),
            GetSleepScoreTool(historyQueries, sleepScores, arguments, format),
            GetHrvStatusTool(hrvStatusHistory, arguments, format, SensorHistoryModule.zone),
            GetTrainingLoadTool(trainingLoadHistory, arguments, format, SensorHistoryModule.zone),
            GetBloodPressureTool(bloodPressureMonitor, sensorHistoryStore, CalibrationAccuracyCalculator(), arguments, format),
            GetDeliveryStatusTool(sensorHistoryStore, arguments, format),
            GetWatchLogsTool(deviceLinkTransport, WatchReplies, arguments),
            TriggerHealthSnapshotTool(watchSnapshotRequester, format),
            StartWorkoutTool(watchWorkoutCommands),
            EndWorkoutTool(watchWorkoutCommands),
            GetSpaceWeatherTool(spaceWeatherStore, SensorHistoryModule.zone, mcpTextResolver),
            GetCardioReadinessTool(cardioReadiness, format),
        )
    }

    private companion object {
        const val WEIGHT_STATE_PREFS = "profile_weight_state"
        const val HEIGHT_STATE_PREFS = "profile_height_state"
        const val AUTO_EXERCISE_STATE_PREFS = "auto_exercise_state"
        const val CARDIO_LOAD_STATE_PREFS = "cardio_load_state"
        const val FATIGUE_INDEX_STATE_PREFS = "fatigue_index_state"
        const val MAX_ORPHAN_CHECK_SESSIONS = 64
        const val CARDIO_READINESS_PUBLISHER_TAG = "ReadinessPublisher"
        val WATCH_WORKOUT_REPLY_TIMEOUT = 1.minutes

        /** Unchanged readiness is re-sent this often: inside the tile's 30 min freshness, and the watch flushes every 30 min. */
        val CARDIO_READINESS_REFRESH: Duration = Duration.ofMinutes(25)

        /** Long enough for a flush's batches to arrive, short enough to answer before the watch goes back to sleep. */
        val WATCH_REPLY_COALESCE_WINDOW = 3.seconds
    }
}
