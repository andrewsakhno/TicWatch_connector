package org.sand.ticwatch.watch.sensors

import android.util.Log
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Receives the per-channel window means of `mobvoi_temperature` so channels the aggregator
 * doesn't use (0 and 3) can be compared against the skin channels and the phone's core
 * estimate - i.e. to find out whether the watch already reports a calibrated body temperature.
 */
fun interface TemperatureChannelSink {
    /** [channelMeans] is indexed by channel; null where the channel never carried a valid value. */
    fun record(windowStart: Instant, windowEnd: Instant, channelMeans: List<Double?>, events: Int)
}

/**
 * Appends one line per measurement window to [logFile] (`start end events ch0,ch1,ch2,ch3`),
 * pulled over the debug bridge together with the raw vendor logs. One line per window keeps
 * months of windows far below [maxBytes]; once reached, recording stops instead of rotating
 * so the oldest (calibration-relevant) lines are never lost.
 */
class FileTemperatureChannelLog(
    private val logFile: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : TemperatureChannelSink {

    override fun record(windowStart: Instant, windowEnd: Instant, channelMeans: List<Double?>, events: Int) {
        val channels = channelMeans.joinToString(",") { mean -> mean?.let { String.format(Locale.ROOT, "%.3f", it) } ?: NO_VALUE }
        Log.i(TAG, "Temperature channels over $events event(s): $channels")
        if (logFile.length() > maxBytes) return
        runCatching { logFile.appendText("$windowStart $windowEnd $events $channels\n") }
            .onFailure { Log.w(TAG, "Temperature channel log write failed", it) }
    }

    companion object {
        private const val TAG = "TemperatureChannels"
        const val LOG_FILE_NAME = "temperature_channels.log"
        private const val DEFAULT_MAX_BYTES = 512L * 1024
        private const val NO_VALUE = "-"
    }
}
