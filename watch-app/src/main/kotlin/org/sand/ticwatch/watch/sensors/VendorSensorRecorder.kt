package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Investigation aid: records every channel of the given Mobvoi vendor sensors to
 * [logFile] so their output can be pulled over the debug bridge and decoded. At most one
 * event per sensor type per [minLogInterval] is kept, so a fast-reporting sensor can run
 * for hours under [MAX_LOG_BYTES]. Registered with a long max report latency so the
 * sensor hub batches events in its FIFO instead of waking the application processor for
 * each one.
 */
class VendorSensorRecorder(
    private val sensorManager: SensorManager,
    private val logFile: File,
    private val sensorTypes: List<Int>,
    private val minLogInterval: Duration = Duration.ZERO,
) : SensorEventListener {

    private val lastLoggedAt = mutableMapOf<Int, Instant>()

    fun start() {
        sensorTypes.forEach { type ->
            val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { it.type == type && !it.isWakeUpSensor }
            val registered = sensor != null && sensorManager.registerListener(
                this, sensor, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY.toNanos().div(NANOS_PER_MICRO).toInt(),
            )
            Log.i(TAG, "Vendor sensor $type registered=$registered -> ${logFile.name}")
        }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        if (logFile.length() > MAX_LOG_BYTES) return
        val at = event.wallClockTime()
        val previous = lastLoggedAt[event.sensor.type]
        if (previous != null && Duration.between(previous, at) < minLogInterval) return
        lastLoggedAt[event.sensor.type] = at
        val line = "$at ${event.sensor.type} ${event.values.joinToString(",")}\n"
        runCatching { logFile.appendText(line) }.onFailure { Log.w(TAG, "Vendor sensor log write failed", it) }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    companion object {
        private const val TAG = "VendorSensorRecorder"
        const val SLEEP_LOG_FILE_NAME = "sleep_sensor.log"
        const val TEMPERATURE_LOG_FILE_NAME = "temperature_sensor.log"
        val SLEEP_SENSOR_TYPES = listOf(VendorSensorTypes.SLEEP, VendorSensorTypes.TICMOTION_SLEEP)
        val TEMPERATURE_SENSOR_TYPES = listOf(VendorSensorTypes.TEMPERATURE)
        private val MAX_REPORT_LATENCY: Duration = Duration.ofMinutes(5)
        private const val NANOS_PER_MICRO = 1_000L
        private const val MAX_LOG_BYTES = 2L * 1024 * 1024
        private const val TAIL_LINES = 400

        /** Debug-bridge view of the logs: per file, its size plus the last [TAIL_LINES] lines. */
        fun probe(logFiles: List<File>) = DeviceCapabilityProbe {
            logFiles.fold(JSONObject()) { result, logFile ->
                val lines = if (logFile.exists()) logFile.readLines() else emptyList()
                result.put(
                    logFile.name,
                    JSONObject()
                        .put("bytes", logFile.length())
                        .put("lines", lines.size)
                        .put("tail", JSONArray(lines.takeLast(TAIL_LINES))),
                )
            }
        }
    }
}
