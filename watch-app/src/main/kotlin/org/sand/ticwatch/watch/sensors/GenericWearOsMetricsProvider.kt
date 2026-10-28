package org.sand.ticwatch.watch.sensors

import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState

/**
 * [ExtendedMetricsProvider] for watches without the Mobvoi vendor sensors (Galaxy Watch, Pixel Watch,
 * OnePlus Watch, ...), built on public APIs only. Health Services has no sleep-stage, SpO2 or skin
 * temperature DataType, so:
 * - sleep sessions come from the passive listener's ASLEEP state (the service's `StateSessionTracker`,
 *   shared by every provider), and this provider feeds the same state into [sleepState] so the phone's
 *   snore monitor still follows the night; no stages - the phone falls back to its HR-based estimate;
 * - SpO2, HRV, breathing rate and skin temperature are not measured: no window group, no night monitor,
 *   hence no PPG or wake-lock cost for sensors that aren't there.
 */
class GenericWearOsMetricsProvider(
    private val sleepState: SleepStateSink,
) : ExtendedMetricsProvider {

    override val kind = MetricsProviderKind.GENERIC_WEAR_OS

    override fun sampleSources(): List<SampleSource> = emptyList()

    override fun windowGroups(): List<AggregatorGroup> = emptyList()

    override fun activityObservers(): List<UserActivityObserver> = listOf(HealthServicesSleepStateObserver(sleepState))

    override fun start() = Unit

    override fun stop() = Unit
}

/** Mirrors Health Services' ASLEEP user-activity state into a [SleepStateSink] (transitions only). */
class HealthServicesSleepStateObserver(private val sink: SleepStateSink) : UserActivityObserver {
    override fun onUserActivity(info: UserActivityInfo) =
        sink.update(info.userActivityState == UserActivityState.USER_ACTIVITY_ASLEEP, info.stateChangeTime)
}
