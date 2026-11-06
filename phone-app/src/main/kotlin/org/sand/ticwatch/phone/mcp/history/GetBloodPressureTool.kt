package org.sand.ticwatch.phone.mcp.history

import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.phone.health.BloodPressureCalibration
import org.sand.ticwatch.phone.health.BloodPressureMonitor
import org.sand.ticwatch.phone.health.BloodPressureReading
import org.sand.ticwatch.phone.history.SleepStage
import org.sand.ticwatch.phone.history.SleepStageQueries
import org.sand.ticwatch.phone.mcp.McpTool
import org.sand.ticwatch.phone.report.CalibrationAccuracy
import org.sand.ticwatch.phone.report.CalibrationAccuracyCalculator
import org.sand.ticwatch.phone.report.CuffComparison
import java.time.Duration

/** Saved blood pressure estimates over a range plus the calibration they extrapolate from and its accuracy. */
class GetBloodPressureTool(
    private val monitor: BloodPressureMonitor,
    private val stages: SleepStageQueries,
    private val accuracyCalculator: CalibrationAccuracyCalculator,
    private val parser: HistoryToolArguments,
    private val format: HistoryToolFormat,
) : McpTool {
    override val name = "get_blood_pressure"
    override val description =
        "Saved blood pressure readings (mmHg) in a range, ascending, with their ESC category and source " +
            "(BLOOD_PRESSURE_CUFF = measured by the Bioland cuff, with pulse; WATCH = wrist estimate), plus the " +
            "calibration: the latest cuff reading and the heart rate / RMSSD / skin temperature at that moment. WATCH " +
            "readings are cuffless estimates shifted from the calibration by heart rate, RMSSD and skin temperature changes " +
            "(systolic within ±35, at most 15 below it while awake at a resting pulse; diastolic within ±15) - use for " +
            "trends, not diagnosis. sleep_stage (AWAKE/LIGHT/DEEP/REM) is the stored sleep stage the reading falls in, " +
            "absent outside sleep or before the night's stages arrived. calibration.stale is " +
            "true after ${BloodPressureMonitor.CALIBRATION_MAX_AGE.toDays()} days. accuracy compares each cuff reading " +
            "in the range with the nearest WATCH estimate within ${CalibrationAccuracyCalculator.MAX_GAP.toMinutes()} " +
            "minutes: cuff_readings_count, paired_comparisons_count, mean_abs_systolic_delta and " +
            "mean_abs_diastolic_delta (null without pairs), comparisons (time = the cuff reading's, delta_sys / " +
            "delta_dia = wrist minus cuff). Raw rows follow the history retention. Default range: the last $DEFAULT_DAYS days."
    override val inputSchema: JSONObject = HistoryToolSchema.dayRangeSchema(DEFAULT_DAYS)

    override suspend fun call(arguments: JSONObject): String {
        val range = parser.rangeWithDefaultSpan(arguments, Duration.ofDays(DEFAULT_DAYS))
        val stored = monitor.log.readings(range.from, range.to)
        val readings = JSONArray()
        val night = stages.sleepStages(range.from, range.to)
        stored.forEach { readings.put(reading(it, stageAt(night, it))) }
        return JSONObject()
            .put("range", format.range(range))
            .put("calibration", monitor.calibration()?.let(::calibration) ?: JSONObject.NULL)
            .put("accuracy", accuracy(accuracyCalculator.compare(stored)))
            .put("readings", readings)
            .toString()
    }

    private fun stageAt(night: List<SleepStage>, reading: BloodPressureReading): SleepStage? =
        night.firstOrNull { !reading.measuredAt.isBefore(it.start) && reading.measuredAt.isBefore(it.end) }

    private fun reading(reading: BloodPressureReading, stage: SleepStage?): JSONObject = JSONObject()
        .put("time", format.time(reading.measuredAt))
        .put("systolic", format.number(reading.systolic))
        .put("diastolic", format.number(reading.diastolic))
        .put("category", format.text(reading.category.label))
        .put("source", reading.source.name)
        .apply { reading.pulse?.let { put("pulse", format.number(it)) } }
        .apply { stage?.let { put("sleep_stage", it.type.name) } }

    private fun calibration(calibration: BloodPressureCalibration): JSONObject = JSONObject()
        .put("time", format.time(calibration.calibratedAt))
        .put("systolic", format.number(calibration.systolic))
        .put("diastolic", format.number(calibration.diastolic))
        .put("heart_rate", format.number(calibration.heartRate))
        .put("rmssd_ms", calibration.rmssdMs?.let(format::number) ?: JSONObject.NULL)
        .put("skin_temperature_c", calibration.skinTemperatureCelsius?.let(format::number) ?: JSONObject.NULL)
        .put("stale", monitor.isStale(calibration))

    private fun accuracy(accuracy: CalibrationAccuracy): JSONObject {
        val comparisons = JSONArray()
        accuracy.comparisons.forEach { comparisons.put(comparison(it)) }
        return JSONObject()
            .put("cuff_readings_count", accuracy.cuffReadings.size)
            .put("paired_comparisons_count", accuracy.comparisons.size)
            .put("mean_abs_systolic_delta", accuracy.meanAbsoluteSystolicDelta?.let(format::number) ?: JSONObject.NULL)
            .put("mean_abs_diastolic_delta", accuracy.meanAbsoluteDiastolicDelta?.let(format::number) ?: JSONObject.NULL)
            .put("comparisons", comparisons)
    }

    private fun comparison(comparison: CuffComparison): JSONObject = JSONObject()
        .put("time", format.time(comparison.cuff.measuredAt))
        .put("cuff", pressure(comparison.cuff))
        .put("wrist", pressure(comparison.wrist))
        .put("delta_sys", format.number(comparison.systolicDelta))
        .put("delta_dia", format.number(comparison.diastolicDelta))

    private fun pressure(reading: BloodPressureReading): JSONObject = JSONObject()
        .put("sys", format.number(reading.systolic))
        .put("dia", format.number(reading.diastolic))

    private companion object {
        const val DEFAULT_DAYS = 30L
    }
}
