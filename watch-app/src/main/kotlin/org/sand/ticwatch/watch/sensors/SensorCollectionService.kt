package org.sand.ticwatch.watch.sensors

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.UserActivityState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import androidx.health.services.client.HealthServices
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutController
import org.sand.ticwatch.watch.bloodpressure.BloodPressureMeasureRequester
import org.sand.ticwatch.watch.bloodpressure.PhoneBloodPressureMeasureRequester
import org.sand.ticwatch.watch.bloodpressure.AutoBloodPressureController
import org.sand.ticwatch.watch.bloodpressure.NoWorkoutGate
import org.sand.ticwatch.watch.bloodpressure.RecentStepsGate
import org.sand.ticwatch.watch.bloodpressure.SharedPreferencesAutoBloodPressureSettings
import org.sand.ticwatch.watch.phonedata.PhonePayloadStores
import org.sand.ticwatch.watch.autoworkout.AutoWorkoutStartLedger
import org.sand.ticwatch.watch.autoworkout.BriskWalkDetector
import org.sand.ticwatch.watch.autoworkout.HealthServicesWorkoutSession
import org.sand.ticwatch.watch.autoworkout.NotificationAutoWorkoutPrompt
import org.sand.ticwatch.watch.autoworkout.SharedPreferencesAutoWorkoutSettings
import org.sand.ticwatch.watch.autoworkout.StepCounterSensorSource
import org.sand.ticwatch.watch.buffer.SqliteSensorSampleBuffer
import org.sand.ticwatch.watch.datalayer.SensorSyncSender
import org.sand.ticwatch.watch.datalayer.WorkoutStateNotifier
import org.sand.ticwatch.watch.power.WakeLockAwakeRunner
import org.sand.ticwatch.watch.route.GpsLocationFixSource
import org.sand.ticwatch.watch.route.WatchRouteRecorder
import org.sand.ticwatch.watch.transport.BluetoothClassicTransport
import java.io.File
import java.time.Clock
import java.time.Duration

/**
 * Foreground service that owns heart-rate collection and the bridge sync to the phone
 * for as long as the watch is powered on. Health Services' [androidx.health.services.client.MeasureClient]
 * only keeps delivering updates to an app that's in the foreground or running a
 * foreground service - collection used to be driven from a `LaunchedEffect` in
 * MainActivity, so it silently stopped the moment the screen turned off or the app was
 * swiped away. Owning it here instead is what makes a full day of samples reach the
 * phone (and from there, Health Connect) rather than only whatever happened to be
 * collected while the activity was on screen. Background heart rate now comes from the
 * passive listener (sensor-hub batched); the Measure API runs only on [LocalBinder.liveHeartRate] demand.
 */
class SensorCollectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()
    private val liveReadings = LiveReadings()

    inner class LocalBinder : Binder() {
        val readings: LiveReadings get() = liveReadings

        /** Hold while a screen shows the reading: switches heart rate from passive batches to live Measure updates. */
        val liveHeartRate: LiveMeasurementDemand get() = liveHeartRateDemand

        /** The blood pressure page's "Measure" button. */
        val bloodPressure: BloodPressureMeasureRequester get() = bloodPressureRequester
    }

    private val bloodPressureRequester: PhoneBloodPressureMeasureRequester by lazy {
        PhoneBloodPressureMeasureRequester(
            flusher = { sensorSyncSender.flushNow() },
            readings = liveReadings,
            transport = transport,
            results = PhonePayloadStores.get(applicationContext).bloodPressureResult,
            clock = Clock.systemUTC(),
            scope = serviceScope,
        )
    }

    private val noWorkoutGate = NoWorkoutGate()

    private val recentStepsGate by lazy {
        RecentStepsGate(
            StepCounterSensorSource(getSystemService(SensorManager::class.java), STEP_REPORT_LATENCY),
            window = BLOOD_PRESSURE_REST_WINDOW,
            maxSteps = BLOOD_PRESSURE_REST_MAX_STEPS,
            clock = Clock.systemUTC(),
        )
    }

    private val autoBloodPressure by lazy {
        AutoBloodPressureController(
            settings = SharedPreferencesAutoBloodPressureSettings(applicationContext),
            gates = listOf(WindowGate(offBodyDetector::isWorn), noWorkoutGate, recentStepsGate),
            stillness = { listOf(GyroscopeStillnessQualifier()) },
            probe = SensorQualifierProbe(getSystemService(SensorManager::class.java)),
            requester = bloodPressureRequester,
            scheduler = AlarmWindowScheduler(
                getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), BLOOD_PRESSURE_ALARM_TAG,
            ),
            awakeRunner = WakeLockAwakeRunner(getSystemService(PowerManager::class.java), BLOOD_PRESSURE_WAKE_LOCK_TAG),
            interval = BLOOD_PRESSURE_INTERVAL,
            probeDuration = BLOOD_PRESSURE_STILLNESS_PROBE,
        )
    }

    // Measure API keeps the PPG lit and wakes the CPU every second, so it only runs while a
    // screen asks for it; background heart rate comes batched from the passive listener.
    private val liveHeartRateDemand by lazy {
        val sensorCollector: SensorCollector = HealthServicesSensorCollector(applicationContext)
        OnDemandCollection(serviceScope) { sensorCollector.heartRateSamples().collect(liveReadings::record) }
    }

    private val vendorSensorRecorders by lazy {
        val sensorManager = getSystemService(SensorManager::class.java)
        listOf(
            VendorSensorRecorder(sensorManager, File(filesDir, VendorSensorRecorder.SLEEP_LOG_FILE_NAME), VendorSensorRecorder.SLEEP_SENSOR_TYPES),
            VendorSensorRecorder(
                sensorManager,
                File(filesDir, VendorSensorRecorder.TEMPERATURE_LOG_FILE_NAME),
                VendorSensorRecorder.TEMPERATURE_SENSOR_TYPES,
                TEMPERATURE_LOG_INTERVAL,
            ),
        )
    }

    private val offBodyDetector by lazy { OffBodyDetector(getSystemService(SensorManager::class.java)) }

    private val sleepState = TrackedSleepState(MAX_SLEEP_EVENT_GAP)

    private val respirationEventLog by lazy {
        FileRawWindowEventLog(File(filesDir, FileRawWindowEventLog.RESPIRATION_LOG_FILE_NAME))
    }

    private val lowPowerHeartRateEventLog by lazy {
        FileRawWindowEventLog(
            File(filesDir, FileRawWindowEventLog.LOW_POWER_HEART_RATE_LOG_FILE_NAME), FileRawWindowEventLog.NIGHT_LOG_MAX_BYTES,
        )
    }

    private val heartRateAlertEventLog by lazy {
        FileRawWindowEventLog(File(filesDir, FileRawWindowEventLog.HEART_RATE_ALERT_LOG_FILE_NAME))
    }

    private val temperatureChannelLog by lazy {
        FileTemperatureChannelLog(File(filesDir, FileTemperatureChannelLog.LOG_FILE_NAME))
    }

    private val autoWorkoutStartLedger by lazy {
        AutoWorkoutStartLedger(getSharedPreferences(AutoWorkoutStartLedger.PREFERENCES_NAME, MODE_PRIVATE), AUTO_WORKOUT_MAX_BACKDATE)
    }

    private val autoWorkout by lazy {
        AutoWorkoutController(
            steps = StepCounterSensorSource(getSystemService(SensorManager::class.java), STEP_REPORT_LATENCY),
            detector = BriskWalkDetector(
                briskCadence = BRISK_WALK_CADENCE,
                movingCadence = MOVING_CADENCE,
                window = CADENCE_WINDOW,
                minSpan = MIN_CADENCE_SPAN,
                maxGap = MAX_WALK_GAP,
            ),
            session = HealthServicesWorkoutSession(HealthServices.getClient(applicationContext).exerciseClient),
            settings = SharedPreferencesAutoWorkoutSettings(applicationContext),
            prompt = NotificationAutoWorkoutPrompt(
                applicationContext, SensorCollectionService::class.java, ACTION_START_AUTO_WORKOUT, ACTION_END_AUTO_WORKOUT,
            ),
            ledger = autoWorkoutStartLedger,
            scheduler = AlarmWindowScheduler(
                getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), AUTO_WORKOUT_ALARM_TAG,
            ),
            minWalk = MIN_AUTO_WALK,
            stopAfter = AUTO_WORKOUT_STOP_AFTER,
            checkInterval = AUTO_WORKOUT_CHECK_INTERVAL,
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Health only: a microphone type is refused when started from the background (boot), see onStartCommand.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        vendorSensorRecorders.forEach { it.start() }
        offBodyDetector.start()

        val backgroundSources: List<SampleSource> =
            listOf(
                buildPassiveSampleSource(), buildVendorWindowSampleSource(), buildSleepStageSampleSource(),
                buildNightBreathingSampleSource(),
                HeartRateAlertSampleSource(getSystemService(SensorManager::class.java), heartRateAlertEventLog),
            )
        val samples = backgroundSources.map { source -> source.samples().onEach(liveReadings::record) }
        serviceScope.launch { sensorSyncSender.sync(merge(*samples.toTypedArray())) }
        serviceScope.launch { autoWorkout.run() }
        serviceScope.launch { recentStepsGate.track() }
        serviceScope.launch { autoBloodPressure.run() }
    }

    // mobvoi_sleep first: the TicMotion sensor only fills in while it is silent.
    private fun buildSleepStageSampleSource(): SampleSource = VendorSleepStageSource(
        sensorManager = getSystemService(SensorManager::class.java),
        sensorTypes = listOf(VendorSensorTypes.SLEEP, VendorSensorTypes.TICMOTION_SLEEP),
        decoder = MobvoiSleepStageDecoder(),
        segmenter = SleepStageSegmenter(minSession = MIN_SLEEP_SESSION, maxGap = MAX_SLEEP_EVENT_GAP),
        sleepState = sleepState,
    )

    private fun buildNightBreathingSampleSource(): SampleSource = NightBreathingSampleSource(
        sensorManager = getSystemService(SensorManager::class.java),
        powerManager = getSystemService(PowerManager::class.java),
        scheduler = AlarmWindowScheduler(
            getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), NIGHT_IDLE_ALARM_TAG,
        ),
        sleepState = sleepState,
        wearState = offBodyDetector,
        epochAggregators = listOf(
            ::Spo2Aggregator,
            ::RmssdAggregator,
            { RriRespirationRateAggregator() },
            { RawEventRecorder(VendorSensorTypes.LOW_POWER_HEART_RATE, lowPowerHeartRateEventLog) },
        ),
        epochQualifiers = listOf(::StillnessQualifier),
        detectors = listOf({ DesaturationDetector() }),
        snoreMeter = MicrophoneSnoreMeter(applicationContext),
        epoch = NIGHT_EPOCH,
        snoreSlot = NIGHT_SNORE_SLOT,
        idleCheckInterval = NIGHT_IDLE_CHECK_INTERVAL,
    )

    private val daytimeBodyGate = WindowGate { offBodyDetector.isWorn() && !sleepState.isAsleep() }

    private fun buildVendorWindowSampleSource(): SampleSource = VendorWindowSampleSource(
        sensorManager = getSystemService(SensorManager::class.java),
        powerManager = getSystemService(PowerManager::class.java),
        scheduler = AlarmWindowScheduler(
            getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), VENDOR_WINDOW_ALARM_TAG,
        ),
        groups = listOf(
            // Body sensors only read sensibly on the wrist; off it they'd just light the PPG. During
            // sleep the night breathing monitor measures them continuously instead.
            AggregatorGroup(
                factories = listOf(::Spo2Aggregator),
                gate = daytimeBodyGate,
                // PPG reads wrist motion as desaturation, so a moving window's SpO2 is dropped.
                qualifiers = listOf(::StillnessQualifier),
            ),
            AggregatorGroup(
                factories = listOf(
                    ::RmssdAggregator,
                    { RriRespirationRateAggregator() },
                    { RawEventRecorder(VendorSensorTypes.RESPIRATION_RATE, respirationEventLog) },
                ),
                gate = daytimeBodyGate,
            ),
            // Skin temperature keeps its 30 min cadence through the night (nightly baseline, body temperature).
            AggregatorGroup(
                factories = listOf({ SkinTemperatureAggregator(temperatureChannelLog) }),
                gate = WindowGate(offBodyDetector::isWorn),
            ),
            // Environment sensors are meaningful whether or not the watch is worn.
            AggregatorGroup(
                factories = listOf(
                    ::AmbientTemperatureAggregator,
                    ::BarometricPressureAggregator,
                    ::AmbientLightAggregator,
                ),
            ),
        ),
        interval = VENDOR_WINDOW_INTERVAL,
        window = VENDOR_WINDOW,
    )

    private val sensorSyncSender by lazy {
        SensorSyncSender(
            transport = transport,
            pendingSamples = SqliteSensorSampleBuffer(applicationContext),
            scheduler = AlarmWindowScheduler(
                getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), SYNC_ALARM_TAG,
            ),
            awakeRunner = WakeLockAwakeRunner(getSystemService(PowerManager::class.java), SYNC_WAKE_LOCK_TAG),
            flushInterval = SYNC_FLUSH_INTERVAL,
            flushThreshold = SYNC_FLUSH_THRESHOLD,
        )
    }

    /** One transport shared by the sample sync, the workout-state notifier and blood pressure requests. */
    private val transport by lazy { BluetoothClassicTransport(applicationContext) }

    private fun buildPassiveSampleSource(): SampleSource {
        val sessionState = getSharedPreferences(StateSessionTracker.PREFERENCES_NAME, MODE_PRIVATE)
        return HealthServicesPassiveSampleSource(
            context = applicationContext,
            bindings = listOf(
                SampleBinding(DataType.HEART_RATE_BPM, SensorType.HEART_RATE),
                IntervalBinding(DataType.STEPS, SensorType.STEPS),
                IntervalBinding(DataType.DISTANCE, SensorType.DISTANCE),
                IntervalBinding(DataType.FLOORS, SensorType.FLOORS),
                IntervalBinding(DataType.ELEVATION_GAIN, SensorType.ELEVATION_GAIN),
                IntervalBinding(DataType.CALORIES, SensorType.CALORIES),
                IntervalBinding(DataType.CALORIES_DAILY, SensorType.CALORIES_DAILY),
            ),
            sessionTrackers = listOf(
                // Health Services has no sleep DataType; its asleep/awake activity state
                // is the only sleep signal available to a third-party watch app.
                StateSessionTracker(sessionState, UserActivityState.USER_ACTIVITY_ASLEEP, SensorType.SLEEP, MIN_SLEEP_SESSION),
                // Workouts run by any Health Services-based exercise app on the watch.
                // An automatic walk's session starts where the walk began, not when it was detected.
                StateSessionTracker(
                    sessionState, UserActivityState.USER_ACTIVITY_EXERCISE, SensorType.EXERCISE, MIN_EXERCISE_SESSION,
                    valueAtOpen = { info -> (info.exerciseInfo?.exerciseType ?: ExerciseType.UNKNOWN).id.toDouble() },
                    startAtOpen = autoWorkoutStartLedger::sessionStart,
                ),
            ),
            // Lets the phone record a GPS route for the workout while it runs, and records the watch's own.
            activityObservers = listOf(WorkoutStateNotifier(transport, serviceScope), buildWatchRouteRecorder(), noWorkoutGate),
        )
    }

    private fun buildWatchRouteRecorder() = WatchRouteRecorder(
        fixSource = GpsLocationFixSource(applicationContext, getSystemService(LocationManager::class.java), ROUTE_FIX_INTERVAL),
        transport = transport,
        scope = serviceScope,
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUTO_WORKOUT -> serviceScope.launch { autoWorkout.onStartRequested() }
            ACTION_END_AUTO_WORKOUT -> serviceScope.launch { autoWorkout.onEndRequested() }
        }
        addMicrophoneForegroundType()
        return START_STICKY
    }

    /**
     * Snore detection needs while-in-use microphone access, which a foreground service only gets when
     * its microphone type is added while the app is in the foreground (MainActivity's start). From the
     * background (boot) Android 14 refuses it - the service then simply runs without snore detection.
     */
    private fun addMicrophoneForegroundType() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Microphone foreground type refused; snoring won't be measured", error)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Microphone foreground type refused; snoring won't be measured", error)
        }
    }

    override fun onDestroy() {
        vendorSensorRecorders.forEach { it.stop() }
        offBodyDetector.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Heart rate sync", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TicWatch Connector")
            .setContentText("Collecting heart rate")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .build()
    }

    companion object {
        private const val TAG = "SensorCollectionService"
        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "sensor_collection"

        private const val ACTION_START_AUTO_WORKOUT = "org.sand.ticwatch.watch.action.START_AUTO_WORKOUT"
        private const val ACTION_END_AUTO_WORKOUT = "org.sand.ticwatch.watch.action.END_AUTO_WORKOUT"

        /** Brisk walking for this long becomes a workout; same cadence as phone-app's AutoExerciseDeriver. */
        private val MIN_AUTO_WALK: Duration = Duration.ofMinutes(5)
        private const val BRISK_WALK_CADENCE = 90.0

        /** Slower steps still keep a started walk going (crowds, stairs). */
        private const val MOVING_CADENCE = 60.0
        private val CADENCE_WINDOW: Duration = Duration.ofMinutes(1)
        private val MIN_CADENCE_SPAN: Duration = Duration.ofSeconds(20)

        /** A traffic light doesn't split a walk. */
        private val MAX_WALK_GAP: Duration = Duration.ofMinutes(2)
        private val AUTO_WORKOUT_STOP_AFTER: Duration = Duration.ofMinutes(3)
        private val AUTO_WORKOUT_CHECK_INTERVAL: Duration = Duration.ofMinutes(1)
        private val AUTO_WORKOUT_MAX_BACKDATE: Duration = Duration.ofMinutes(30)

        /** Wake-up step counter batches: while walking the watch wakes this often, idle it never does. */
        private val STEP_REPORT_LATENCY: Duration = Duration.ofSeconds(30)
        private const val AUTO_WORKOUT_ALARM_TAG = "TicWatchConnector:AutoWorkoutAlarm"

        /** Automatic blood pressure: every 5 min while worn, no workout, few steps and a still wrist (gyroscope). */
        private val BLOOD_PRESSURE_INTERVAL: Duration = Duration.ofMinutes(5)
        private val BLOOD_PRESSURE_REST_WINDOW: Duration = Duration.ofMinutes(5)
        private const val BLOOD_PRESSURE_REST_MAX_STEPS = 30L
        private val BLOOD_PRESSURE_STILLNESS_PROBE: Duration = Duration.ofSeconds(20)
        private const val BLOOD_PRESSURE_ALARM_TAG = "TicWatchConnector:BloodPressureAlarm"
        private const val BLOOD_PRESSURE_WAKE_LOCK_TAG = "TicWatchConnector:BloodPressureProbe"

        /** Shorter asleep blips (e.g. lying still on a couch) aren't recorded as sleep. */
        private val MIN_SLEEP_SESSION: Duration = Duration.ofMinutes(15)
        private val MIN_EXERCISE_SESSION: Duration = Duration.ofMinutes(3)

        /** Same cadence as phone-app's WorkoutLocationService track. */
        private val ROUTE_FIX_INTERVAL: Duration = Duration.ofSeconds(4)

        /**
         * Longer sleep-sensor silences (watch off, sensor stopped) end the night at the last event. The
         * vendor sensor only reports stage changes, and single stages ran up to 51 min on the first night.
         */
        private val MAX_SLEEP_EVENT_GAP: Duration = Duration.ofHours(2)

        /** One all-channel temperature line per minute: ~6.5 h fit the debug pull's tail. */
        private val TEMPERATURE_LOG_INTERVAL: Duration = Duration.ofMinutes(1)

        /** SpO2 needs ~2 min of PPG to converge, so each window is 3 min, every 30 min. */
        private val VENDOR_WINDOW_INTERVAL: Duration = Duration.ofMinutes(30)
        private val VENDOR_WINDOW: Duration = Duration.ofMinutes(3)
        private const val VENDOR_WINDOW_ALARM_TAG = "TicWatchConnector:VendorWindowAlarm"

        /** Night epochs: 5 min of SpO2/HRV/breathing each, the first minute of it also listening for snoring. */
        private val NIGHT_EPOCH: Duration = Duration.ofMinutes(5)
        private val NIGHT_SNORE_SLOT: Duration = Duration.ofMinutes(1)

        /** How often an idle night monitor rechecks whether a sleep session has begun. */
        private val NIGHT_IDLE_CHECK_INTERVAL: Duration = Duration.ofMinutes(5)
        private const val NIGHT_IDLE_ALARM_TAG = "TicWatchConnector:NightIdleAlarm"

        /** Samples reach the phone within this long at most; a full batch goes out sooner. */
        private val SYNC_FLUSH_INTERVAL: Duration = Duration.ofMinutes(15)
        private const val SYNC_FLUSH_THRESHOLD = 50
        private const val SYNC_ALARM_TAG = "TicWatchConnector:SyncFlushAlarm"
        private const val SYNC_WAKE_LOCK_TAG = "TicWatchConnector:SyncFlush"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SensorCollectionService::class.java))
        }
    }
}
