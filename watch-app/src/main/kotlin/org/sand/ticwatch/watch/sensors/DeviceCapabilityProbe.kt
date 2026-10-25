package org.sand.ticwatch.watch.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.health.services.client.HealthServices
import androidx.health.services.client.getCapabilities
import org.json.JSONArray
import org.json.JSONObject

/** Describes what health data this device can expose to a third-party app (debug bridge). */
fun interface DeviceCapabilityProbe {
    suspend fun describe(): JSONObject
}

/**
 * Reports Health Services passive/measure/exercise capabilities, every [SensorManager]
 * sensor (vendor ones included, which is where SpO2 / skin temperature / respiration
 * would show up if the OEM exposes them at all), and content providers of
 * [VENDOR_PACKAGE_MARKER] packages that might hold vendor-computed metrics.
 */
class HealthDeviceCapabilityProbe(private val context: Context) : DeviceCapabilityProbe {

    override suspend fun describe(): JSONObject {
        val client = HealthServices.getClient(context)
        val passive = client.passiveMonitoringClient.getCapabilities()
        return JSONObject()
            .put("passive", JSONArray(passive.supportedDataTypesPassiveMonitoring.map { it.name }))
            .put("userActivityStates", JSONArray(passive.supportedUserActivityStates.map { it.name }))
            .put("measure", JSONArray(client.measureClient.getCapabilities().supportedDataTypesMeasure.map { it.name }))
            .put("exercise", JSONArray(client.exerciseClient.getCapabilities().supportedExerciseTypes.map { it.name }))
            .put("sensors", JSONArray(sensors()))
            .put("vendorProviders", JSONArray(vendorProviders()))
    }

    private fun sensors(): List<String> =
        context.getSystemService(SensorManager::class.java)
            .getSensorList(Sensor.TYPE_ALL)
            .map { "${it.type}|${it.stringType}|${it.name}|${it.vendor}" }

    private fun vendorProviders(): List<String> =
        context.packageManager
            .getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PROVIDERS.toLong()))
            .filter { VENDOR_PACKAGE_MARKER in it.packageName }
            .flatMap { pkg ->
                pkg.providers.orEmpty().map { "${pkg.packageName}|${it.authority}|exported=${it.exported}|read=${it.readPermission}" }
            }

    private companion object {
        const val VENDOR_PACKAGE_MARKER = "mobvoi"
    }
}
