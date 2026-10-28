package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log

/** `Build.MANUFACTURER` / `Build.BRAND` of the watch, injected so the selection stays testable. */
class DeviceIdentity(val manufacturer: String, val brand: String) {
    fun matches(vendor: String): Boolean =
        manufacturer.equals(vendor, ignoreCase = true) || brand.equals(vendor, ignoreCase = true)
}

/**
 * Picks the [ExtendedMetricsProvider] for this watch. The Mobvoi provider needs its vendor sensors, and their
 * ids differ between models, so presence of [REQUIRED_MOBVOI_SENSORS] decides - a Mobvoi brand alone (another
 * TicWatch model) gets the generic provider; the brand is only reported. Providers are built lazily, so the
 * unused one costs nothing.
 */
class MetricsProviderFactory(
    private val sensorManager: SensorManager,
    private val device: DeviceIdentity,
    private val mobvoi: () -> ExtendedMetricsProvider,
    private val generic: () -> ExtendedMetricsProvider,
) {

    fun create(): ExtendedMetricsProvider {
        val available = sensorManager.getSensorList(Sensor.TYPE_ALL).mapTo(HashSet()) { it.type }
        val hasMobvoiSensors = available.containsAll(REQUIRED_MOBVOI_SENSORS)
        val provider = if (hasMobvoiSensors) mobvoi() else generic()
        Log.i(
            TAG,
            "Metrics provider ${provider.kind} (manufacturer=${device.manufacturer}, brand=${device.brand}, " +
                "mobvoiBrand=${device.matches(MOBVOI_VENDOR)}, mobvoiSensors=$hasMobvoiSensors)",
        )
        return provider
    }

    private companion object {
        const val TAG = "MetricsProviderFactory"
        const val MOBVOI_VENDOR = "Mobvoi"

        /** Sleep and PPG sensors every Mobvoi feature depends on; the rest are optional and just log "not found". */
        val REQUIRED_MOBVOI_SENSORS = setOf(VendorSensorTypes.SLEEP, VendorSensorTypes.SPO2)
    }
}
