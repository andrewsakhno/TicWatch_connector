package org.sand.ticwatch.watch.ecg

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Clock

/** One raw PPG reading of the pulsatile channel at wall-clock [atEpochMs]. */
data class PpgSample(val atEpochMs: Long, val value: Long)

/** Streams the raw pulse wave while collected. */
fun interface PpgWaveSource {
    fun samples(): Flow<PpgSample>
}

/**
 * `pah815x_ppg` (`com.google.wear.sensor.ppg`, PixArt): ~32 Hz raw PPG. Of its channels only [pulseChannel] carries
 * a clean pulse (probe 2026-10-29: 0/1 weak and noisy, 2 constant, 4 no pulse). Event times are boot-clock nanos,
 * mapped to the wall clock so they line up with the beats.
 */
class VendorPpgWaveSource(
    private val sensorManager: SensorManager,
    private val clock: Clock,
    private val pulseChannel: Int = PULSE_CHANNEL,
) : PpgWaveSource {

    override fun samples(): Flow<PpgSample> = callbackFlow {
        val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.stringType == PPG_STRING_TYPE && !it.isWakeUpSensor }
        if (sensor == null) {
            Log.w(TAG, "Raw PPG sensor not found; the strip is drawn from the beats only")
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size <= pulseChannel) return
                val ageMs = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / NANOS_PER_MILLI
                trySend(PpgSample(clock.millis() - ageMs, event.values[pulseChannel].toLong()))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    companion object {
        const val PPG_STRING_TYPE = "com.google.wear.sensor.ppg"
        const val PULSE_CHANNEL = 3
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val TAG = "VendorPpgWaveSource"
    }
}
