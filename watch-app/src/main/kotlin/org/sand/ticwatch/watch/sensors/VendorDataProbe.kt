package org.sand.ticwatch.watch.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

/**
 * Debug probe for the vendor (Mobvoi) data path that Health Services doesn't expose:
 * samples the vendor health sensors for [SAMPLE_WINDOW] and reports what they emit,
 * reports the protection level and grant state of Mobvoi's data permissions, and tries
 * querying Mobvoi's health content providers. Answers whether SpO2 / respiration /
 * skin temperature / sleep stages are reachable by a third-party app at all.
 */
class VendorDataProbe(private val context: Context) : DeviceCapabilityProbe {

    override suspend fun describe(): JSONObject =
        JSONObject()
            .put("permissions", permissions())
            .put("providers", providers())
            .put("sensors", sampleSensors())

    private suspend fun sampleSensors(): JSONObject {
        val sensorManager = context.getSystemService(SensorManager::class.java)
        val targets = sensorManager.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
            !sensor.isWakeUpSensor && VENDOR_SENSOR_MARKERS.any { it in sensor.name }
        }
        val recorders = targets.map(::SensorRecorder)
        val registered = recorders.associateWith { recorder ->
            sensorManager.registerListener(recorder, recorder.sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        delay(SAMPLE_WINDOW.toMillis())
        recorders.forEach { sensorManager.unregisterListener(it) }

        return JSONObject().apply {
            registered.forEach { (recorder, ok) -> put(recorder.sensor.name, recorder.report().put("registered", ok)) }
        }
    }

    private fun permissions(): JSONObject = JSONObject().apply {
        VENDOR_PERMISSIONS.forEach { permission ->
            val protection = runCatching {
                val info = context.packageManager.getPermissionInfo(permission, 0)
                PROTECTION_NAMES[info.protection] ?: info.protection.toString()
            }.getOrElse { "missing" }
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            put(permission, "$protection|granted=$granted")
        }
    }

    private fun providers(): JSONObject = JSONObject().apply {
        VENDOR_PROVIDER_AUTHORITIES.forEach { authority ->
            val outcome = runCatching {
                context.contentResolver.query(Uri.parse("content://$authority"), null, null, null, null).use { cursor ->
                    cursor?.let(::describeRows) ?: "null cursor"
                }
            }.getOrElse { error -> error.toString() }
            put(authority, outcome)
        }
    }

    private fun describeRows(cursor: Cursor): JSONArray = JSONArray().apply {
        while (cursor.moveToNext() && length() < MAX_PROVIDER_ROWS) {
            put(JSONObject().apply {
                cursor.columnNames.forEachIndexed { index, column -> put(column, cursor.getString(index)) }
            })
        }
    }

    /** Keeps the event count plus the first and the latest [MAX_RECORDED_EVENTS] events of one sensor. */
    private class SensorRecorder(val sensor: Sensor) : SensorEventListener {
        private val first = mutableListOf<String>()
        private val latest = ArrayDeque<String>()
        private var count = 0

        override fun onSensorChanged(event: SensorEvent) {
            count++
            val line = "acc=${event.accuracy} ${event.values.dropLastWhile { it == 0f }.joinToString(",")}"
            if (first.size < MAX_RECORDED_EVENTS) first += line
            latest.addLast(line)
            if (latest.size > MAX_RECORDED_EVENTS) latest.removeFirst()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        fun report(): JSONObject = JSONObject()
            .put("type", sensor.type)
            .put("count", count)
            .put("first", JSONArray(first))
            .put("latest", JSONArray(latest))
    }

    private companion object {
        val SAMPLE_WINDOW: Duration = Duration.ofSeconds(180)
        const val MAX_RECORDED_EVENTS = 5
        const val MAX_PROVIDER_ROWS = 5
        val VENDOR_SENSOR_MARKERS = listOf("spo2", "psp_rr", "temperature", "sleep", "lphr", "psp_all")
        val VENDOR_PERMISSIONS = listOf(
            "com.mobvoi.wear.permission.HEALTH_DATA_NORMAL",
            "com.mobvoi.ticwear.permission.HEALTH",
            "com.mobvoi.ticwear.permission.FITNESS_DATA",
        )
        val VENDOR_PROVIDER_AUTHORITIES = listOf(
            "com.mobvoi.health.data.provider",
            "com.mobvoi.heartrate.db.provider",
            "com.mobvoi.ticwear.steps",
            "com.mobvoi.health.db.provider",
            "com.mobvoi.fitness.db.provider",
        )
        val PROTECTION_NAMES = mapOf(
            PermissionInfo.PROTECTION_NORMAL to "normal",
            PermissionInfo.PROTECTION_DANGEROUS to "dangerous",
            PermissionInfo.PROTECTION_SIGNATURE to "signature",
            PermissionInfo.PROTECTION_INTERNAL to "internal",
        )
    }
}
