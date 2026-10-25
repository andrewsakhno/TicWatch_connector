package org.sand.ticwatch.phone.mcp.history

import org.sand.ticwatch.phone.health.SensorType
import org.sand.ticwatch.phone.history.RollupPoint

/** One statistic of a rollup bucket; [argName] is how tools accept and report it. */
enum class SeriesStat(val argName: String) {
    AVG("avg") {
        override fun of(point: RollupPoint) = point.average
    },
    MIN("min") {
        override fun of(point: RollupPoint) = point.min
    },
    MAX("max") {
        override fun of(point: RollupPoint) = point.max
    },
    SUM("sum") {
        override fun of(point: RollupPoint) = point.sum
    },
    COUNT("count") {
        override fun of(point: RollupPoint) = point.count.toDouble()
    };

    abstract fun of(point: RollupPoint): Double
}

/** How a sensor type's values combine over time, which decides the statistic that summarises a bucket. */
enum class MetricKind(val defaultStat: SeriesStat, val guidance: String) {
    INSTANTANEOUS(SeriesStat.AVG, "point measurements: use avg/min/max"),
    CUMULATIVE(SeriesStat.SUM, "per-interval increments: use sum"),
    RUNNING_TOTAL(SeriesStat.MAX, "running total since local midnight: use max per day"),
    SESSION(SeriesStat.COUNT, "intervals with start/end: read them from export_samples or get_raw_samples"),
}

/** What one sensor type's value means. */
data class MetricDescriptor(val unit: String, val kind: MetricKind, val note: String)

/** Units and semantics of every [SensorType], so an external model can interpret and aggregate values correctly. */
class SensorMetricCatalog {

    fun describe(type: SensorType): MetricDescriptor = descriptors.getValue(type)

    private companion object {
        val descriptors: Map<SensorType, MetricDescriptor> = mapOf(
            SensorType.HEART_RATE to MetricDescriptor("bpm", MetricKind.INSTANTANEOUS, "passive heart rate from the watch"),
            SensorType.RESTING_HEART_RATE to MetricDescriptor(
                "bpm", MetricKind.INSTANTANEOUS, "one phone-derived value per day",
            ),
            SensorType.STEPS to MetricDescriptor("steps", MetricKind.CUMULATIVE, "steps per interval"),
            SensorType.CALORIES to MetricDescriptor("kcal", MetricKind.CUMULATIVE, "active calories only, basal excluded"),
            SensorType.CALORIES_DAILY to MetricDescriptor(
                "kcal", MetricKind.RUNNING_TOTAL, "total calories incl. basal since local midnight",
            ),
            SensorType.DISTANCE to MetricDescriptor("m", MetricKind.CUMULATIVE, "distance per interval"),
            SensorType.FLOORS to MetricDescriptor("floors", MetricKind.CUMULATIVE, "floors climbed per interval"),
            SensorType.ELEVATION_GAIN to MetricDescriptor("m", MetricKind.CUMULATIVE, "elevation gained per interval"),
            SensorType.SPEED to MetricDescriptor("m/s", MetricKind.INSTANTANEOUS, "phone-derived from distance"),
            SensorType.SPO2 to MetricDescriptor("%", MetricKind.INSTANTANEOUS, "blood oxygen saturation"),
            SensorType.HRV_RMSSD to MetricDescriptor("ms", MetricKind.INSTANTANEOUS, "heart rate variability (RMSSD)"),
            SensorType.SKIN_TEMPERATURE to MetricDescriptor("°C", MetricKind.INSTANTANEOUS, "wrist skin temperature"),
            SensorType.BODY_TEMPERATURE to MetricDescriptor(
                "°C", MetricKind.INSTANTANEOUS, "phone-derived from skin, ambient and heart rate",
            ),
            SensorType.AMBIENT_TEMPERATURE to MetricDescriptor(
                "°C", MetricKind.INSTANTANEOUS, "watch case (NST112) temperature, window mean; warmed by the wrist when worn",
            ),
            SensorType.RESPIRATION_RATE to MetricDescriptor(
                "breaths/min", MetricKind.INSTANTANEOUS, "respiration rate, window median of the watch's psp_rr sensor",
            ),
            SensorType.BAROMETRIC_PRESSURE to MetricDescriptor(
                "hPa", MetricKind.INSTANTANEOUS, "atmospheric pressure (mspc110), window mean; measured on or off the wrist",
            ),
            SensorType.AMBIENT_LIGHT to MetricDescriptor(
                "lux", MetricKind.INSTANTANEOUS, "illuminance (opt3007), window mean; a sleeve covering the watch reads dark",
            ),
            SensorType.VO2_MAX to MetricDescriptor("ml/kg/min", MetricKind.INSTANTANEOUS, "phone-derived estimate"),
            SensorType.SLEEP to MetricDescriptor("session", MetricKind.SESSION, "sleep session; start/end are what matter"),
            SensorType.SLEEP_STAGE to MetricDescriptor(
                "stage", MetricKind.SESSION,
                "sleep stage interval; value 1=awake 2=light 3=deep 4=rem (see get_sleep_sessions for per-session stages)",
            ),
            SensorType.STEPS_CADENCE to MetricDescriptor(
                "steps/min", MetricKind.INSTANTANEOUS, "phone-derived from walking/running step intervals",
            ),
            SensorType.BASAL_METABOLIC_RATE to MetricDescriptor(
                "kcal/day", MetricKind.INSTANTANEOUS, "phone-derived Mifflin-St Jeor estimate from the profile, one per day",
            ),
            SensorType.EXERCISE to MetricDescriptor(
                "session", MetricKind.SESSION, "exercise session; value is the Health Services exercise type id",
            ),
        )

        init {
            check(descriptors.keys == SensorType.entries.toSet()) { "Every SensorType needs a MetricDescriptor" }
        }
    }
}
