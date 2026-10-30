package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.sand.ticwatch.watch.ecg.VendorPpgWaveSource
import org.sand.ticwatch.watch.power.AwakeRunner
import java.time.Duration

/**
 * Debug probe for decoding the raw PPG sensor (`pah815x_ppg`, `com.google.wear.sensor.ppg`): records every event of
 * it next to `psp_rri` (the vendor's detected beats) for [window], so a pulsing channel can be matched to the beats
 * offline. Reports each sensor's descriptor and a CSV of all events (`sensor,t_ns,accuracy,values...`, values
 * untrimmed so the channel layout stays visible).
 */
class RawPpgProbe(
    private val sensorManager: SensorManager,
    private val awakeRunner: AwakeRunner,
    private val window: Duration = DEFAULT_WINDOW,
) : DeviceCapabilityProbe {

    override suspend fun describe(): JSONObject {
        val targets = sensorManager.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
            !sensor.isWakeUpSensor && (sensor.stringType == VendorPpgWaveSource.PPG_STRING_TYPE || sensor.type == VendorSensorTypes.RR_INTERVAL)
        }
        val csv = StringBuilder(CSV_HEADER)
        val counts = targets.associateWith { 0 }.toMutableMap()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = synchronized(csv) {
                counts[event.sensor] = (counts[event.sensor] ?: 0) + 1
                csv.append('\n').append(event.sensor.type).append(',').append(event.timestamp).append(',')
                    .append(event.accuracy).append(',').append(event.values.joinToString(","))
                Unit
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val registered = awakeRunner.runAwake(window.plus(WAKE_LOCK_MARGIN)) {
            val result = targets.associateWith { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST) }
            try {
                delay(window.toMillis())
            } finally {
                sensorManager.unregisterListener(listener)
            }
            result
        }
        return JSONObject()
            .put("window_ms", window.toMillis())
            .put("sensors", JSONObject().apply { targets.forEach { put(it.name, describe(it, registered[it] == true, counts[it] ?: 0)) } })
            .put("csv", synchronized(csv) { csv.toString() })
    }

    private fun describe(sensor: Sensor, registered: Boolean, events: Int): JSONObject = JSONObject()
        .put("type", sensor.type)
        .put("string_type", sensor.stringType)
        .put("vendor", sensor.vendor)
        .put("min_delay_us", sensor.minDelay)
        .put("max_delay_us", sensor.maxDelay)
        .put("fifo_max", sensor.fifoMaxEventCount)
        .put("resolution", sensor.resolution.toDouble())
        .put("max_range", sensor.maximumRange.toDouble())
        .put("registered", registered)
        .put("events", events)

    companion object {
        const val WAKE_LOCK_TAG = "TicWatchConnector:RawPpgProbe"
        val DEFAULT_WINDOW: Duration = Duration.ofSeconds(40)
        private val WAKE_LOCK_MARGIN: Duration = Duration.ofSeconds(10)
        private const val CSV_HEADER = "sensor_type,t_ns,accuracy,values"
    }
}
