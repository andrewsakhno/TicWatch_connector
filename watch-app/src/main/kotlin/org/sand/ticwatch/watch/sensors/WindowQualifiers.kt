package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Judges a whole measurement window from a sensor of its own: unlike a [WindowGate] (checked only at
 * the window start and end) it listens throughout, and the group's results are dropped unless it
 * [accepts] the window.
 */
interface WindowQualifier : WindowSensorListener {
    fun accepts(): Boolean
}

/**
 * Accelerometer stillness: an event is moving when the acceleration magnitude strays more than
 * [MOVING_DEVIATION] from gravity (turning the wrist doesn't change the magnitude, swinging it does);
 * the window is rejected when more than [MAX_MOVING_FRACTION] of events are moving. PPG readings such
 * as SpO2 read motion artifacts as desaturation. No events (sensor missing) counts as still.
 * Thresholds are uncalibrated - the logged fraction is there to tune them.
 */
class StillnessQualifier : WindowQualifier {
    override val sensorType = Sensor.TYPE_ACCELEROMETER
    private var events = 0
    private var movingEvents = 0

    override fun onEvent(values: FloatArray, at: Instant) {
        if (values.size < AXES) return
        val magnitude = sqrt((0 until AXES).sumOf { values[it].toDouble() * values[it] })
        events++
        if (abs(magnitude - SensorManager.GRAVITY_EARTH) > MOVING_DEVIATION) movingEvents++
    }

    override fun accepts(): Boolean {
        if (events == 0) return true
        val movingFraction = movingEvents.toDouble() / events
        Log.i(TAG, "Window moving fraction ${"%.2f".format(movingFraction)} over $events events")
        return movingFraction <= MAX_MOVING_FRACTION
    }

    private companion object {
        const val TAG = "StillnessQualifier"
        const val AXES = 3
        const val MOVING_DEVIATION = 1.5
        const val MAX_MOVING_FRACTION = 0.15
    }
}

/**
 * Gyroscope stillness: an event is moving when the angular speed exceeds [MOVING_RAD_PER_SECOND] (about 20 °/s -
 * gestures, typing, a swinging arm); the window is rejected when more than [MAX_MOVING_FRACTION] of events move.
 * Catches the wrist motion a step counter misses (sitting but busy). No events (sensor missing) counts as still.
 * Thresholds are uncalibrated - the logged fraction is there to tune them.
 */
class GyroscopeStillnessQualifier : WindowQualifier {
    override val sensorType = Sensor.TYPE_GYROSCOPE
    private var events = 0
    private var movingEvents = 0

    override fun onEvent(values: FloatArray, at: Instant) {
        if (values.size < AXES) return
        val angularSpeed = sqrt((0 until AXES).sumOf { values[it].toDouble() * values[it] })
        events++
        if (angularSpeed > MOVING_RAD_PER_SECOND) movingEvents++
    }

    override fun accepts(): Boolean {
        if (events == 0) return true
        val movingFraction = movingEvents.toDouble() / events
        Log.i(TAG, "Gyroscope moving fraction ${"%.2f".format(movingFraction)} over $events events")
        return movingFraction <= MAX_MOVING_FRACTION
    }

    private companion object {
        const val TAG = "GyroscopeStillness"
        const val AXES = 3
        const val MOVING_RAD_PER_SECOND = 0.35
        const val MAX_MOVING_FRACTION = 0.1
    }
}
