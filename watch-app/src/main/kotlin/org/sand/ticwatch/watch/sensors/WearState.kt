package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/** Whether the watch is currently on the wrist. */
fun interface WearStateSource {
    fun isWorn(): Boolean
}

/**
 * [WearStateSource] backed by `TYPE_LOW_LATENCY_OFFBODY_DETECT`: an on-change wake-up sensor
 * whose values[0] is 1 on the wrist and [OFF_BODY] off it. Until its first event (or if the sensor is
 * missing) the watch counts as worn, so a silent detector never blocks measurements.
 */
class OffBodyDetector(private val sensorManager: SensorManager) : WearStateSource, SensorEventListener {

    @Volatile
    private var worn = true

    fun start() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT, true)
        val registered = sensor != null && sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        Log.i(TAG, "Off-body detector registered=$registered")
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun isWorn(): Boolean = worn

    override fun onSensorChanged(event: SensorEvent) {
        val state = event.values.getOrNull(STATE_INDEX) ?: return
        worn = state != OFF_BODY
        Log.i(TAG, "Wear state changed: worn=$worn")
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val TAG = "OffBodyDetector"
        const val STATE_INDEX = 0
        const val OFF_BODY = 0f
    }
}
