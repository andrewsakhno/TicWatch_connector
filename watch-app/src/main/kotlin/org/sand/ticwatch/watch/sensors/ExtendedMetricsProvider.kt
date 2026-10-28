package org.sand.ticwatch.watch.sensors

/** Which [ExtendedMetricsProvider] implementation serves this watch. */
enum class MetricsProviderKind {
    /** Mobvoi vendor sensors (`mobvoi_sleep`, `psp_spo2`, `psp_rri`, `mobvoi_temperature`): TicWatch. */
    MOBVOI,

    /** Only public Wear OS APIs (Health Services, standard `Sensor.TYPE_*`): any other watch. */
    GENERIC_WEAR_OS,
}

/**
 * Source of the device-specific "extended" metrics - sleep stages/sessions, SpO2, HRV, breathing rate,
 * skin temperature - which only some watches expose, each through its own sensors (Strategy, picked by
 * [MetricsProviderFactory]). Everything else (passive Health Services data, environment sensors, the
 * sync pipeline and the wire format to the phone) is shared and unaware of the provider: a provider only
 * contributes more [SensorSample]s of the existing [SensorType]s.
 */
interface ExtendedMetricsProvider {
    val kind: MetricsProviderKind

    /** Background streams of their own (sleep stages, the night breathing monitor), merged into the sync. */
    fun sampleSources(): List<SampleSource>

    /** Groups that join the shared periodic measurement window (see [VendorWindowSampleSource]). */
    fun windowGroups(): List<AggregatorGroup>

    /** Observers of Health Services' user-activity state, added to the passive listener. */
    fun activityObservers(): List<UserActivityObserver>

    /** Called with the hosting service's onCreate / onDestroy, for listeners that live outside a flow. */
    fun start()
    fun stop()
}
