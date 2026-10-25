package org.sand.ticwatch.watch.sensors

import android.util.Log
import java.io.File
import java.time.Instant

/** One raw sensor event as delivered, every channel kept. */
class RawSensorEvent(val at: Instant, val values: FloatArray)

/**
 * Receives every raw event an aggregator saw during one measurement window, so a vendor
 * sensor whose layout isn't decoded yet (`psp_rr`) can be inspected without a continuous
 * listener keeping its emitter lit between windows.
 */
fun interface RawWindowEventSink {
    fun record(sensorType: Int, windowStart: Instant, windowEnd: Instant, events: List<RawSensorEvent>)
}

/**
 * Appends a `# window <start> <end> <count>` header and one `<at> <type> v0,v1,...` line per
 * event to [logFile] (the [VendorSensorRecorder] line format), in one write per window. Stops
 * at [maxBytes] instead of rotating, so the first windows are never lost.
 */
class FileRawWindowEventLog(
    private val logFile: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : RawWindowEventSink {

    override fun record(sensorType: Int, windowStart: Instant, windowEnd: Instant, events: List<RawSensorEvent>) {
        Log.i(TAG, "Sensor $sensorType: ${events.size} raw event(s) in window, last=${events.lastOrNull()?.values?.joinToString(",")}")
        if (logFile.length() > maxBytes) return
        val block = buildString {
            append("$WINDOW_HEADER $windowStart $windowEnd ${events.size}\n")
            events.forEach { append("${it.at} $sensorType ${it.values.joinToString(",")}\n") }
        }
        runCatching { logFile.appendText(block) }.onFailure { Log.w(TAG, "Raw window event log write failed", it) }
    }

    companion object {
        private const val TAG = "RawWindowEvents"
        const val RESPIRATION_LOG_FILE_NAME = "respiration_sensor.log"
        private const val WINDOW_HEADER = "# window"
        private const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
    }
}
