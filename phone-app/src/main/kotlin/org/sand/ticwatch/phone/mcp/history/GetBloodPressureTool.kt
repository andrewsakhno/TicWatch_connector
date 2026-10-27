package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.mcp.McpTool
import java.time.Duration

/** Saved blood pressure estimates over a range plus the calibration they extrapolate from. */
class GetBloodPressureTool(
    private val monitor: BloodPressureMonitor,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_blood_pressure"
    override val description =
        "Saved blood pressure readings (mmHg) in a range, ascending, with their ESC category and source " +
            "(BLOOD_PRESSURE_CUFF = measured by the Bioland cuff, with pulse; WATCH = wrist estimate), plus the " +
            "calibration: the latest cuff reading and the heart rate / RMSSD / skin temperature at that moment. WATCH " +
            "readings are cuffless estimates shifted from the calibration by heart rate, RMSSD and skin temperature changes " +
            "(systolic within ±25, diastolic within ±15 of it) - use for trends, not diagnosis. calibration.stale is " +
            "true after ${BloodPressureMonitor.CALIBRATION_MAX_AGE.toDays()} days. Raw rows follow the history " +
            "retention. Default range: the last $DEFAULT_DAYS days."
    override val inputSchema: JSONObject = HistoryToolSchema.dayRangeSchema(DEFAULT_DAYS)

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, Duration.ofDays(DEFAULT_DAYS))
        val readings = JSONArray()
        monitor.log.readings(range.from, range.to).forEach { readings.put(reading(it)) }
        return JSONObject()
            .put("range", format.range(range))
            .put("calibration", monitor.calibration()?.let(::calibration) ?: JSONObject.NULL)
            .put("readings", readings)
            .toString()
    }

    private fun reading(reading: BloodPressureReading): JSONObject = JSONObject()
        .put("time", format.time(reading.measuredAt))
        .put("systolic", format.number(reading.systolic))
        .put("diastolic", format.number(reading.diastolic))
        .put("category", reading.category.label)
        .put("source", reading.source.name)
        .apply { reading.pulse?.let { put("pulse", format.number(it)) } }

    private fun calibration(calibration: BloodPressureCalibration): JSONObject = JSONObject()
        .put("time", format.time(calibration.calibratedAt))
        .put("systolic", format.number(calibration.systolic))
        .put("diastolic", format.number(calibration.diastolic))
        .put("heart_rate", format.number(calibration.heartRate))
        .put("rmssd_ms", calibration.rmssdMs?.let(format::number) ?: JSONObject.NULL)
        .put("skin_temperature_c", calibration.skinTemperatureCelsius?.let(format::number) ?: JSONObject.NULL)
        .put("stale", monitor.isStale(calibration))

    private companion object {
        const val DEFAULT_DAYS = 30L
    }
}
