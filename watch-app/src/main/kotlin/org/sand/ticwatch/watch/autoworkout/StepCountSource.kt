package org.sand.ticwatch.watch.autoworkout

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import org.sand.ticwatch.watch.sensors.wallClockTime
import java.time.Duration
import java.time.Instant

/** Cumulative step count since boot at [at]. */
data class StepCount(val at: Instant, val total: Long)

/** Live step counts, delivered while the flow is collected. */
fun interface StepCountSource {
    fun steps(): Flow<StepCount>
}

/**
 * The platform step counter (sensor hub). The wake-up variant is preferred: it only reports while the
 * wearer steps, batched by [maxReportLatency], so walking wakes the watch every batch and standing still
 * costs nothing. Without one, events wait in the hub FIFO until something else wakes the CPU.
 */
class StepCounterSensorSource(
    private val sensorManager: SensorManager,
    private val maxReportLatency: Duration,
) : StepCountSource {

    override fun steps(): Flow<StepCount> {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.also {
                Log.w(TAG, "No wake-up step counter; walks are noticed only when the watch wakes")
            }
            ?: return emptyFlow<StepCount>().also { Log.w(TAG, "No step counter; auto workouts disabled") }
        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(StepCount(event.wallClockTime(), event.values[0].toLong()))
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            sensorManager.registerListener(
                listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, maxReportLatency.toNanos().div(NANOS_PER_MICRO).toInt(),
            )
            awaitClose { sensorManager.unregisterListener(listener) }
        }
    }

    private companion object {
        const val TAG = "StepCounterSensorSource"
        const val NANOS_PER_MICRO = 1_000L
    }
}
