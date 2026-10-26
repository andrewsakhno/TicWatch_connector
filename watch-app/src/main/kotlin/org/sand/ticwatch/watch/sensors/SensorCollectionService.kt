package org.sand.ticwatch.watch.sensors

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
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
import org.sand.ticwatch.watch.buffer.SqliteSensorSampleBuffer
import org.sand.ticwatch.watch.datalayer.SensorSyncSender
import org.sand.ticwatch.watch.datalayer.WorkoutStateNotifier
import org.sand.ticwatch.watch.power.WakeLockAwakeRunner
import org.sand.ticwatch.watch.transport.BluetoothClassicTransport
import java.io.File
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

    private val respirationEventLog by lazy {
        FileRawWindowEventLog(File(filesDir, FileRawWindowEventLog.RESPIRATION_LOG_FILE_NAME))
    }

    private val temperatureChannelLog by lazy {
        FileTemperatureChannelLog(File(filesDir, FileTemperatureChannelLog.LOG_FILE_NAME))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        vendorSensorRecorders.forEach { it.start() }
        offBodyDetector.start()

        val backgroundSources: List<SampleSource> =
            listOf(buildPassiveSampleSource(), buildVendorWindowSampleSource(), buildSleepStageSampleSource())
        val sensorSyncSender = SensorSyncSender(
            transport = transport,
            pendingSamples = SqliteSensorSampleBuffer(applicationContext),
            scheduler = AlarmWindowScheduler(
                getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), SYNC_ALARM_TAG,
            ),
            awakeRunner = WakeLockAwakeRunner(getSystemService(PowerManager::class.java), SYNC_WAKE_LOCK_TAG),
            flushInterval = SYNC_FLUSH_INTERVAL,
            flushThreshold = SYNC_FLUSH_THRESHOLD,
        )
        val samples = backgroundSources.map { source -> source.samples().onEach(liveReadings::record) }
        serviceScope.launch { sensorSyncSender.sync(merge(*samples.toTypedArray())) }
    }

    // mobvoi_sleep first: the TicMotion sensor only fills in while it is silent.
    private fun buildSleepStageSampleSource(): SampleSource = VendorSleepStageSource(
        sensorManager = getSystemService(SensorManager::class.java),
        sensorTypes = listOf(VendorSensorTypes.SLEEP, VendorSensorTypes.TICMOTION_SLEEP),
        decoder = MobvoiSleepStageDecoder(),
        segmenter = SleepStageSegmenter(minSession = MIN_SLEEP_SESSION, maxGap = MAX_SLEEP_EVENT_GAP),
    )

    private fun buildVendorWindowSampleSource(): SampleSource = VendorWindowSampleSource(
        sensorManager = getSystemService(SensorManager::class.java),
        powerManager = getSystemService(PowerManager::class.java),
        scheduler = AlarmWindowScheduler(
            getSystemService(AlarmManager::class.java), getSystemService(PowerManager::class.java), VENDOR_WINDOW_ALARM_TAG,
        ),
        groups = listOf(
            // Body sensors only read sensibly on the wrist; off it they'd just light the PPG.
            AggregatorGroup(
                factories = listOf(::Spo2Aggregator),
                gate = WindowGate(offBodyDetector::isWorn),
                // PPG reads wrist motion as desaturation, so a moving window's SpO2 is dropped.
                qualifiers = listOf(::StillnessQualifier),
            ),
            AggregatorGroup(
                factories = listOf(
                    ::RmssdAggregator,
                    { RespirationRateAggregator(respirationEventLog) },
                    { SkinTemperatureAggregator(temperatureChannelLog) },
                ),
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

    /** One transport shared by the sample sync and the workout-state notifier. */
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
                StateSessionTracker(sessionState, UserActivityState.USER_ACTIVITY_EXERCISE, SensorType.EXERCISE, MIN_EXERCISE_SESSION) { info ->
                    (info.exerciseInfo?.exerciseType ?: ExerciseType.UNKNOWN).id.toDouble()
                },
            ),
            // Lets the phone record a GPS route for the workout while it runs.
            activityObservers = listOf(WorkoutStateNotifier(transport, serviceScope)),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "sensor_collection"

        /** Shorter asleep blips (e.g. lying still on a couch) aren't recorded as sleep. */
        private val MIN_SLEEP_SESSION: Duration = Duration.ofMinutes(15)
        private val MIN_EXERCISE_SESSION: Duration = Duration.ofMinutes(3)

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
