package org.sand.ticwatch.phone.health

import java.time.ZoneId

/**
 * Profile weight and height into Health Connect, so Google Health's calorie and BMI figures use the same
 * body as this app's estimates: one [type] sample whenever the profile [value] differs from the last one
 * emitted ([log]), stamped at the push's first sample. Nothing while the value isn't set.
 */
class ProfileMeasurementDeriver(
    private val type: SensorType,
    private val value: suspend () -> Double?,
    private val log: DailyValueLog,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val at = samples.minOfOrNull { it.recordedAt } ?: return emptyList()
        val current = value() ?: return emptyList()
        if (log.lastEmitted()?.value == current) return emptyList()
        log.record(DailyValue(at.atZone(zoneId).toLocalDate(), current))
        return listOf(SensorSample(type, current, at))
    }
}
