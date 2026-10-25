package org.sand.ticwatch.watch.watchface

import org.sand.ticwatch.watch.sensors.LatestReadings
import org.sand.ticwatch.watch.sensors.SensorType
import kotlin.math.roundToInt

/** Display text of the readings shared by the watch face and the tile. */
object ReadingText {
    const val NO_VALUE = "--"
    const val HEART_RATE_UNIT = "BPM"
    const val TEMPERATURE_UNIT = "°C"
    private const val TEMPERATURE_FORMAT = "%.1f"

    fun heartRate(readings: LatestReadings): String =
        readings.current(SensorType.HEART_RATE)?.roundToInt()?.toString() ?: NO_VALUE

    fun ambientTemperature(readings: LatestReadings): String =
        readings.current(SensorType.AMBIENT_TEMPERATURE)?.let { TEMPERATURE_FORMAT.format(it) } ?: NO_VALUE
}
