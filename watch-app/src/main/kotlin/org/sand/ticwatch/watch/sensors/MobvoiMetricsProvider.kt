package org.sand.ticwatch.watch.sensors

import android.hardware.SensorManager
import android.os.PowerManager
import org.sand.ticwatch.watch.sensors.adaptive.MeasurementGroup
import java.time.Duration

/** Raw-event and channel logs the Mobvoi sensors are recorded into for decoding (pulled with the sleep-log debug pull). */
class MobvoiRawLogs(
    val respiration: RawWindowEventSink,
    val lowPowerHeartRate: RawWindowEventSink,
    val heartRateAlert: RawWindowEventSink,
    val temperatureChannels: TemperatureChannelSink,
)

/** Pacing of the Mobvoi sleep segmenter and night breathing monitor. */
class MobvoiMetricsTiming(
    val minSleepSession: Duration,
    val maxSleepEventGap: Duration,
    val nightEpoch: Duration,
    val nightSnoreSlot: Duration,
    val nightIdleCheckInterval: Duration,
)

/**
 * [ExtendedMetricsProvider] over the TicWatch's undocumented Mobvoi vendor sensors (ids in
 * [VendorSensorTypes]): sleep stages from `mobvoi_sleep`/TicMotion sleep, the all-night breathing monitor,
 * daytime SpO2 / HRV / breathing-rate / skin-temperature windows and the raw `mobvoi_hr_alert` recording.
 */
class MobvoiMetricsProvider(
    private val sensorManager: SensorManager,
    private val powerManager: PowerManager,
    private val nightScheduler: WindowScheduler,
    private val sleepState: SleepState,
    private val sleepStateSink: SleepStateSink,
    private val wearState: WearStateSource,
    private val daytimeBodyGate: WindowGate,
    private val wornGate: WindowGate,
    private val snoreMeter: SnoreMeter,
    private val logs: MobvoiRawLogs,
    private val recorders: List<VendorSensorRecorder>,
    private val timing: MobvoiMetricsTiming,
) : ExtendedMetricsProvider {

    override val kind = MetricsProviderKind.MOBVOI

    override fun sampleSources(): List<SampleSource> = listOf(
        buildSleepStageSampleSource(),
        buildNightBreathingSampleSource(),
        HeartRateAlertSampleSource(sensorManager, logs.heartRateAlert),
    )

    override fun windowGroups(): List<AggregatorGroup> = listOf(
        // Body sensors only read sensibly on the wrist; off it they'd just light the PPG. During
        // sleep the night breathing monitor measures them continuously instead.
        AggregatorGroup(
            group = MeasurementGroup.SPO2,
            factories = listOf(::Spo2Aggregator),
            gate = daytimeBodyGate,
            // PPG reads wrist motion as desaturation, so a moving window's SpO2 is dropped.
            qualifiers = listOf(::StillnessQualifier),
        ),
        AggregatorGroup(
            group = MeasurementGroup.HRV,
            factories = listOf(
                ::RmssdAggregator,
                { RriRespirationRateAggregator() },
                { RawEventRecorder(VendorSensorTypes.RESPIRATION_RATE, logs.respiration) },
            ),
            gate = daytimeBodyGate,
        ),
        // Skin temperature keeps its 30 min cadence through the night (nightly baseline, body temperature).
        AggregatorGroup(
            group = MeasurementGroup.SKIN_TEMPERATURE,
            factories = listOf({ SkinTemperatureAggregator(logs.temperatureChannels) }),
            gate = wornGate,
        ),
    )

    // Health Services reports no ASLEEP state on the TicWatch; the vendor sleep sensor is the only sleep signal.
    override fun activityObservers(): List<UserActivityObserver> = emptyList()

    override fun start() = recorders.forEach { it.start() }

    override fun stop() = recorders.forEach { it.stop() }

    // mobvoi_sleep first: the TicMotion sensor only fills in while it is silent.
    private fun buildSleepStageSampleSource(): SampleSource = VendorSleepStageSource(
        sensorManager = sensorManager,
        sensorTypes = listOf(VendorSensorTypes.SLEEP, VendorSensorTypes.TICMOTION_SLEEP),
        decoder = MobvoiSleepStageDecoder(),
        segmenter = SleepStageSegmenter(minSession = timing.minSleepSession, maxGap = timing.maxSleepEventGap),
        sleepState = sleepStateSink,
    )

    private fun buildNightBreathingSampleSource(): SampleSource = NightBreathingSampleSource(
        sensorManager = sensorManager,
        powerManager = powerManager,
        scheduler = nightScheduler,
        sleepState = sleepState,
        wearState = wearState,
        epochAggregators = listOf(
            ::Spo2Aggregator,
            ::RmssdAggregator,
            { RriRespirationRateAggregator() },
            { RawEventRecorder(VendorSensorTypes.LOW_POWER_HEART_RATE, logs.lowPowerHeartRate) },
        ),
        epochQualifiers = listOf(::StillnessQualifier),
        detectors = listOf({ DesaturationDetector() }),
        snoreMeter = snoreMeter,
        epoch = timing.nightEpoch,
        snoreSlot = timing.nightSnoreSlot,
        idleCheckInterval = timing.nightIdleCheckInterval,
    )
}
