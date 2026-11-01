package org.sand.ticwatch.watch.sensors

import org.sand.ticwatch.watch.ecg.VendorPpgWaveSource

import android.hardware.Sensor

import android.hardware.SensorManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.sand.ticwatch.watch.logging.LogCapture
import org.sand.ticwatch.watch.logging.LogCaptureController
import org.sand.ticwatch.watch.sensors.adaptive.MeasurementGroup
import java.time.Clock
import java.time.Duration

/** Raw-event and channel logs the Mobvoi sensors are recorded into for decoding (pulled with the sleep-log debug pull). */
class MobvoiRawLogs(
    val lowPowerHeartRate: RawWindowEventSink,
    val heartRateAlert: RawWindowEventSink,
    val temperatureChannels: TemperatureChannelSink,
)

/** Pacing of the Mobvoi sleep segmenter and night breathing monitor. */
class MobvoiMetricsTiming(
    val minSleepSession: Duration,
    val maxSleepEventGap: Duration,
    val sleepWakeMerge: WakeMergePolicy,
    val sleepCheckInterval: Duration,
    val nightEpoch: Duration,
    val nightSnoreSlot: Duration,
    val nightIdleCheckInterval: () -> Duration,
    val nightRest: Duration,
)

/**
 * The vendor sensors recorded only to decode them; they cost battery, so they run only while the user's "Log capture"
 * setting says so. [enabled] follows the setting, [isOn] reads it now, [scope] runs the recorders' on/off switching.
 */
class MobvoiDiagnostics(
    val enabled: Flow<Boolean>,
    val isOn: () -> Boolean,
    val scope: CoroutineScope,
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
    private val sleepCheckScheduler: WindowScheduler,
    private val sleepState: SleepState,
    private val sleepStateSink: SleepStateSink,
    private val openSleepSessionStore: OpenSleepSessionStore,
    private val sleepWakeEvidence: List<WakeEvidence>,
    private val clock: Clock,
    private val wearState: WearStateSource,
    private val daytimeBodyGate: WindowGate,
    private val wornGate: WindowGate,
    /** Closed while the wearer walks: PPG windows would be rejected for motion, so they don't even start. */
    private val stillEnoughGate: WindowGate,
    private val snoreMeter: SnoreMeter,
    private val logs: MobvoiRawLogs,
    private val recorders: List<VendorSensorRecorder>,
    private val timing: MobvoiMetricsTiming,
    /** The synced "pulse wave at night" setting: raw PPG all night (battery) for pulse amplitude drops. */
    private val nightPulseWave: () -> Boolean,
    /** The synced "intermittent night monitor" setting: pauses between night epochs. */
    private val nightIntermittent: () -> Boolean,
    private val diagnostics: MobvoiDiagnostics,
) : ExtendedMetricsProvider {

    override val kind = MetricsProviderKind.MOBVOI

    override fun sampleSources(): List<SampleSource> = listOf(
        buildSleepStageSampleSource(),
        buildNightBreathingSampleSource(),
        GatedSampleSource(diagnostics.enabled, HeartRateAlertSampleSource(sensorManager, logs.heartRateAlert)),
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
            startGate = stillEnoughGate,
        ),
        // Same PPG, so it must stop on motion too for the emitter to go dark; RR intervals through motion are noise anyway.
        AggregatorGroup(
            group = MeasurementGroup.HRV,
            factories = listOf(
                ::RmssdAggregator,
                { RriRespirationRateAggregator() },
            ),
            gate = daytimeBodyGate,
            qualifiers = listOf(::StillnessQualifier),
            startGate = stillEnoughGate,
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

    override fun start() = LogCaptureController(
        capture = object : LogCapture {
            override fun start() = recorders.forEach { it.start() }
            override fun stop() = recorders.forEach { it.stop() }
        },
        enabled = diagnostics.enabled,
    ).bind(diagnostics.scope)

    override fun stop() = recorders.forEach { it.stop() }

    // mobvoi_sleep first: the TicMotion sensor only fills in while it is silent.
    private fun buildSleepStageSampleSource(): SampleSource = VendorSleepStageSource(
        sensorManager = sensorManager,
        sensorTypes = listOf(VendorSensorTypes.SLEEP, VendorSensorTypes.TICMOTION_SLEEP),
        decoder = MobvoiSleepStageDecoder(),
        segmenter = SleepStageSegmenter(
            minSession = timing.minSleepSession,
            maxGap = timing.maxSleepEventGap,
            wakeMerge = timing.sleepWakeMerge,
        ),
        sleepState = sleepStateSink,
        store = openSleepSessionStore,
        scheduler = sleepCheckScheduler,
        wakeEvidence = sleepWakeEvidence,
        checkInterval = timing.sleepCheckInterval,
        clock = clock,
    )

    private fun ppgSensorType(): Int? =
        sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == VendorPpgWaveSource.PPG_STRING_TYPE }?.type

    private fun buildNightBreathingSampleSource(): SampleSource = NightBreathingSampleSource(
        sensorManager = sensorManager,
        powerManager = powerManager,
        scheduler = nightScheduler,
        sleepState = sleepState,
        wearState = wearState,
        epochAggregators = {
            listOfNotNull(
                ::Spo2Aggregator,
                ::RmssdAggregator,
                { RriRespirationRateAggregator() },
                if (diagnostics.isOn()) ({ RawEventRecorder(VendorSensorTypes.LOW_POWER_HEART_RATE, logs.lowPowerHeartRate) }) else null,
            )
        },
        epochQualifiers = listOf(::StillnessQualifier),
        detectors = {
            listOfNotNull(
                DesaturationDetector(),
                ppgSensorType()?.takeIf { nightPulseWave() }?.let(::PulseAmplitudeDropDetector),
            )
        },
        snoreMeter = snoreMeter,
        epoch = timing.nightEpoch,
        snoreSlot = timing.nightSnoreSlot,
        idleCheckInterval = timing.nightIdleCheckInterval,
        rest = { timing.nightRest.takeIf { nightIntermittent() } },
    )
}
