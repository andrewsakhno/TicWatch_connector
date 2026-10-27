package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/**
 * `mobvoi_hr_alert` ("Mobvoi Inactive HR Sensor", wake-up only): registered for as long as the service runs
 * and expected to fire only when the resting heart rate crosses a vendor threshold, so it costs nothing
 * between events. Its layout is not decoded yet: every event goes raw to [rawEvents] and becomes a
 * [SensorType.HEART_RATE_ALERT] point whose value is values[0] as delivered. The reporting mode is
 * unknown too, so a one-shot sensor is armed as a trigger and re-armed after each event.
 */
class HeartRateAlertSampleSource(
    private val sensorManager: SensorManager,
    private val rawEvents: RawWindowEventSink,
    private val sensorType: Int = VendorSensorTypes.HEART_RATE_ALERT,
) : SampleSource {

    override fun samples(): Flow<SensorSample> = callbackFlow {
        val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == sensorType }
        if (sensor == null) {
            Log.w(TAG, "Sensor $sensorType not found")
            awaitClose()
            return@callbackFlow
        }
        Log.i(TAG, "Sensor $sensorType '${sensor.name}' reportingMode=${sensor.reportingMode} wakeUp=${sensor.isWakeUpSensor}")

        fun onAlert(values: FloatArray, at: Instant) {
            rawEvents.record(sensorType, at, at, listOf(RawSensorEvent(at, values.copyOf())))
            values.getOrNull(VALUE_INDEX)?.let { trySend(SensorSample(SensorType.HEART_RATE_ALERT, it.toDouble(), at)) }
        }

        if (sensor.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            val trigger = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent) {
                    onAlert(event.values, Instant.now())
                    sensorManager.requestTriggerSensor(this, sensor)
                }
            }
            Log.i(TAG, "Trigger armed=${sensorManager.requestTriggerSensor(trigger, sensor)}")
            awaitClose { sensorManager.cancelTriggerSensor(trigger, sensor) }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = onAlert(event.values, event.wallClockTime())
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            Log.i(TAG, "Listener registered=${sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)}")
            awaitClose { sensorManager.unregisterListener(listener) }
        }
    }

    private companion object {
        const val TAG = "HeartRateAlertSource"
        const val VALUE_INDEX = 0
    }
}
