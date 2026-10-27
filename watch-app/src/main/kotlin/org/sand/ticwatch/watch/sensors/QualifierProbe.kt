package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.delay
import java.time.Duration

/** Runs [WindowQualifier]s on their own, outside a measurement window. */
fun interface QualifierProbe {
    /** Listens for [duration] and returns whether every qualifier accepts it. */
    suspend fun accepts(qualifiers: List<WindowQualifier>, duration: Duration): Boolean
}

/**
 * [QualifierProbe] on [SensorManager] listeners; the caller keeps the CPU awake for [duration]. A qualifier whose
 * sensor is missing gets no events, which every qualifier counts as accepted.
 */
class SensorQualifierProbe(private val sensorManager: SensorManager) : QualifierProbe {

    override suspend fun accepts(qualifiers: List<WindowQualifier>, duration: Duration): Boolean {
        val listeners = qualifiers.mapNotNull { qualifier ->
            val sensor = sensorManager.getDefaultSensor(qualifier.sensorType)
                ?: return@mapNotNull null.also { Log.w(TAG, "No sensor ${qualifier.sensorType} for ${qualifier.javaClass.simpleName}") }
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) = qualifier.onEvent(event.values, event.wallClockTime())
                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            listener
        }
        try {
            delay(duration.toMillis())
        } finally {
            listeners.forEach { sensorManager.unregisterListener(it) }
        }
        return qualifiers.all { it.accepts() }
    }

    private companion object {
        const val TAG = "SensorQualifierProbe"
    }
}
