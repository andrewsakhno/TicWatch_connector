package org.sand.ticwatch.watch.sensors

import android.hardware.Sensor
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Folds one sensor's events from a single measurement window into at most one
 * [SensorSample] (Strategy: each sensor's value layout is decoded in exactly one place).
 * Mobvoi vendor layouts were reverse-engineered from on-device probes (see
 * [VendorDataProbe]); Mobvoi doesn't document them.
 */
interface VendorReadingAggregator : WindowSensorListener {
    fun result(windowStart: Instant, windowEnd: Instant): SensorSample?
}

/** Consumes one sensor's events for the duration of a measurement window. */
interface WindowSensorListener {
    /** Android sensor type this listener decodes: a [VendorSensorTypes] id or a standard `Sensor.TYPE_*`. */
    val sensorType: Int
    fun onEvent(values: FloatArray, at: Instant)
}

/** Median of a non-empty list: resists the stray events a window mean would absorb. */
internal fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
}

/** Mobvoi vendor sensor type ids on the TicWatch Pro 5 Enduro. */
object VendorSensorTypes {
    const val SPO2 = 33171103
    const val RR_INTERVAL = 33171105
    const val SLEEP = 33171123
    const val TICMOTION_SLEEP = 33171126
    const val RESPIRATION_RATE = 33171205
    const val TEMPERATURE = 33171602
}

/**
 * `psp_spo2`: values[0] is SpO2 %, values[1] a status that reaches [MIN_VALID_STATUS]
 * once the algorithm has converged (earlier events read 0). Produces the median of the converged
 * readings, so a single motion-artifact dip doesn't become the window's value; fewer than
 * [MIN_READINGS] converged readings yield nothing.
 */
class Spo2Aggregator : VendorReadingAggregator {
    override val sensorType = VendorSensorTypes.SPO2
    private val readings = mutableListOf<Double>()
    private var lastReadingAt: Instant? = null

    override fun onEvent(values: FloatArray, at: Instant) {
        val percent = values.getOrNull(PERCENT_INDEX)?.toDouble() ?: return
        val status = values.getOrNull(STATUS_INDEX) ?: return
        if (status >= MIN_VALID_STATUS && percent in VALID_PERCENT) {
            readings += percent
            lastReadingAt = at
        }
    }

    override fun result(windowStart: Instant, windowEnd: Instant): SensorSample? {
        val at = lastReadingAt
        if (readings.size < MIN_READINGS || at == null) return null
        return SensorSample(SensorType.SPO2, readings.median(), at)
    }

    private companion object {
        const val PERCENT_INDEX = 0
        const val STATUS_INDEX = 1
        const val MIN_VALID_STATUS = 2f
        const val MIN_READINGS = 5
        val VALID_PERCENT = 70.0..100.0
    }
}

/**
 * `psp_rri`: values[0] is one beat-to-beat interval (ms), values[1] its quality
 * ([GOOD_QUALITY] = clean). Produces RMSSD over consecutive clean intervals, skipping
 * jumps larger than [MAX_SUCCESSIVE_DIFF_MS] (ectopic beats / motion artifacts).
 */
class RmssdAggregator : VendorReadingAggregator {
    override val sensorType = VendorSensorTypes.RR_INTERVAL
    private val intervals = mutableListOf<Double>()

    override fun onEvent(values: FloatArray, at: Instant) {
        val interval = values.getOrNull(INTERVAL_INDEX)?.toDouble() ?: return
        val quality = values.getOrNull(QUALITY_INDEX) ?: return
        if (quality == GOOD_QUALITY && interval in VALID_INTERVAL_MS) intervals += interval
    }

    override fun result(windowStart: Instant, windowEnd: Instant): SensorSample? {
        val diffs = intervals.zipWithNext { a, b -> b - a }.filter { abs(it) <= MAX_SUCCESSIVE_DIFF_MS }
        if (diffs.size < MIN_DIFFS) return null
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        return SensorSample(SensorType.HRV_RMSSD, rmssd, windowEnd)
    }

    private companion object {
        const val INTERVAL_INDEX = 0
        const val QUALITY_INDEX = 1
        const val GOOD_QUALITY = 3f
        const val MAX_SUCCESSIVE_DIFF_MS = 250.0
        const val MIN_DIFFS = 30
        val VALID_INTERVAL_MS = 300.0..2000.0
    }
}

/**
 * `mobvoi_temperature`: four channels; [SKIN_CHANNELS] are the two that track the wrist
 * (they drift with skin warming/cooling, ~33-34 °C), the other two look like derived
 * estimates. Produces the window mean of the skin channels; every channel's window mean
 * (0 and 3 included) goes to [channelSink] for calibration checks.
 */
class SkinTemperatureAggregator(
    private val channelSink: TemperatureChannelSink,
) : VendorReadingAggregator {
    override val sensorType = VendorSensorTypes.TEMPERATURE
    private val readings = mutableListOf<Double>()
    private val channelSums = DoubleArray(CHANNEL_COUNT)
    private val channelCounts = IntArray(CHANNEL_COUNT)
    private var events = 0

    override fun onEvent(values: FloatArray, at: Instant) {
        events++
        for (channel in 0 until CHANNEL_COUNT) {
            val value = values.getOrNull(channel)?.toDouble() ?: continue
            if (value !in VALID_CELSIUS) continue
            channelSums[channel] += value
            channelCounts[channel]++
        }
        val channels = SKIN_CHANNELS.mapNotNull { values.getOrNull(it)?.toDouble() }
        if (channels.size == SKIN_CHANNELS.size && channels.all { it in VALID_CELSIUS }) readings += channels.average()
    }

    override fun result(windowStart: Instant, windowEnd: Instant): SensorSample? {
        if (events > 0) {
            val means = (0 until CHANNEL_COUNT).map { if (channelCounts[it] == 0) null else channelSums[it] / channelCounts[it] }
            channelSink.record(windowStart, windowEnd, means, events)
        }
        return if (readings.size < MIN_READINGS) null
        else SensorSample(SensorType.SKIN_TEMPERATURE, readings.average(), windowStart, windowEnd)
    }

    private companion object {
        const val CHANNEL_COUNT = 4
        val SKIN_CHANNELS = listOf(1, 2)
        const val MIN_READINGS = 10
        val VALID_CELSIUS = 20.0..42.0
    }
}

/**
 * Standard environment sensors (on-change like the NST112 thermometer and the light sensor,
 * or continuous like the barometer): a steady value may yield just the first event, so each
 * value is weighted by how long it held - until the next event or the window end - rather
 * than averaged per event. Values outside [validRange] are dropped.
 */
open class TimeWeightedMeanAggregator(
    override val sensorType: Int,
    private val outputType: SensorType,
    private val validRange: ClosedFloatingPointRange<Double>,
) : VendorReadingAggregator {
    private val readings = mutableListOf<Pair<Instant, Double>>()

    override fun onEvent(values: FloatArray, at: Instant) {
        val value = values.getOrNull(VALUE_INDEX)?.toDouble() ?: return
        if (value in validRange) readings += at to value
    }

    override fun result(windowStart: Instant, windowEnd: Instant): SensorSample? {
        if (readings.isEmpty()) return null
        val sorted = readings.sortedBy { it.first }
        val weights = sorted.mapIndexed { index, (at, _) ->
            val heldUntil = sorted.getOrNull(index + 1)?.first ?: windowEnd
            Duration.between(maxOf(at, windowStart), heldUntil).toMillis().coerceAtLeast(0L).toDouble()
        }
        val totalWeight = weights.sum()
        val mean = if (totalWeight > 0) sorted.indices.sumOf { sorted[it].second * weights[it] } / totalWeight
        else sorted.map { it.second }.average()
        return SensorSample(outputType, mean, windowStart, windowEnd)
    }

    private companion object {
        const val VALUE_INDEX = 0
    }
}

/** `TYPE_AMBIENT_TEMPERATURE`: the NST112 in the watch case (°C); worn, it reads warmer than the room. */
class AmbientTemperatureAggregator : TimeWeightedMeanAggregator(
    Sensor.TYPE_AMBIENT_TEMPERATURE, SensorType.AMBIENT_TEMPERATURE, VALID_CELSIUS,
) {
    private companion object {
        val VALID_CELSIUS = -30.0..60.0
    }
}

/** `TYPE_PRESSURE`: the `mspc110` barometer (hPa). */
class BarometricPressureAggregator : TimeWeightedMeanAggregator(
    Sensor.TYPE_PRESSURE, SensorType.BAROMETRIC_PRESSURE, VALID_HPA,
) {
    private companion object {
        val VALID_HPA = 300.0..1100.0
    }
}

/** `TYPE_LIGHT`: the `opt3007` ambient light sensor (lux). */
class AmbientLightAggregator : TimeWeightedMeanAggregator(
    Sensor.TYPE_LIGHT, SensorType.AMBIENT_LIGHT, VALID_LUX,
) {
    private companion object {
        val VALID_LUX = 0.0..200_000.0
    }
}

/**
 * `psp_rr`: values[0] read as breaths/min. The layout is not confirmed - an early probe saw
 * ~64 there, closer to a heart rate - so only physiologically plausible rates
 * ([VALID_BREATHS_PER_MINUTE]) count, and the window median resists stray events. Every raw
 * event goes to [rawEvents] so the real layout can be decoded.
 */
class RespirationRateAggregator(
    private val rawEvents: RawWindowEventSink,
) : VendorReadingAggregator {
    override val sensorType = VendorSensorTypes.RESPIRATION_RATE
    private val rates = mutableListOf<Double>()
    private val events = mutableListOf<RawSensorEvent>()

    override fun onEvent(values: FloatArray, at: Instant) {
        events += RawSensorEvent(at, values.copyOf())
        val rate = values.getOrNull(RATE_INDEX)?.toDouble() ?: return
        if (rate in VALID_BREATHS_PER_MINUTE) rates += rate
    }

    override fun result(windowStart: Instant, windowEnd: Instant): SensorSample? {
        rawEvents.record(sensorType, windowStart, windowEnd, events)
        if (rates.size < MIN_READINGS) return null
        return SensorSample(SensorType.RESPIRATION_RATE, rates.median(), windowStart, windowEnd)
    }

    private companion object {
        const val RATE_INDEX = 0
        const val MIN_READINGS = 3
        val VALID_BREATHS_PER_MINUTE = 4.0..40.0
    }
}
