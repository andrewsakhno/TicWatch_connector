package org.sand.ticwatch.phone.health

import androidx.health.connect.client.records.ActivityIntensityRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Activity intensity as an [SensorType.ACTIVITY_INTENSITY] sample's value; [code] is the stored value,
 * [minReserveFraction] the share of heart-rate reserve (Karvonen) it starts at - ACSM's 40% moderate /
 * 60% vigorous bands, the basis of Google's Heart Points.
 */
enum class ActivityIntensityLevel(val code: Int, val healthConnectType: Int, val minReserveFraction: Double) {
    MODERATE(1, ActivityIntensityRecord.ACTIVITY_INTENSITY_TYPE_MODERATE, 0.40),
    VIGOROUS(2, ActivityIntensityRecord.ACTIVITY_INTENSITY_TYPE_VIGOROUS, 0.60);

    companion object {
        fun fromCode(code: Int): ActivityIntensityLevel? = entries.firstOrNull { it.code == code }

        /** The highest level [reserveFraction] reaches, or null below moderate. */
        fun forReserve(reserveFraction: Double): ActivityIntensityLevel? =
            entries.lastOrNull { reserveFraction >= it.minReserveFraction }
    }
}

/**
 * Moderate/vigorous minutes from the push's heart rate: each minute's mean HR as a fraction of the heart-
 * rate reserve (resting HR from [restingHeartRate] to the age-predicted [TanakaMaxHeartRate]). Only runs
 * of at least [minRun] active minutes count - a single elevated minute is more often stress or a stair
 * than exercise - and each run is emitted as one interval per consecutive level. Needs the birth year.
 */
class ActivityIntensityDeriver(
    private val profile: UserProfileStore,
    private val restingHeartRate: RestingHeartRateBaseline,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val minRun: Int = 2,
    private val minReadingsPerMinute: Int = 3,
) : SampleDeriver {

    private class Minute(val start: Instant, val level: ActivityIntensityLevel)

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val heartRate = samples.filter { it.type == SensorType.HEART_RATE && it.value > 0 }
        if (heartRate.isEmpty()) return emptyList()
        val birthYear = profile.birthYear() ?: return emptyList()
        val minutes = heartRate.groupBy { it.recordedAt.truncatedTo(ChronoUnit.MINUTES) }.toSortedMap()
            .mapNotNull { (start, readings) ->
                if (readings.size < minReadingsPerMinute) return@mapNotNull null
                val resting = restingHeartRate.bpm(start, samples) ?: return@mapNotNull null
                val max = TanakaMaxHeartRate.bpm(start.atZone(zoneId).year - birthYear)
                if (max <= resting) return@mapNotNull null
                val reserve = (readings.map { it.value }.average() - resting) / (max - resting)
                ActivityIntensityLevel.forReserve(reserve)?.let { Minute(start, it) }
            }
        return consecutiveRuns(minutes).filter { it.size >= minRun }.flatMap(::levelIntervals)
    }

    private fun consecutiveRuns(minutes: List<Minute>): List<List<Minute>> {
        val runs = mutableListOf<MutableList<Minute>>()
        minutes.forEach { minute ->
            val run = runs.lastOrNull()
            if (run != null && run.last().start.plus(ONE_MINUTE) == minute.start) run += minute else runs += mutableListOf(minute)
        }
        return runs
    }

    private fun levelIntervals(run: List<Minute>): List<SensorSample> {
        val intervals = mutableListOf<SensorSample>()
        var segmentStart = run.first()
        run.forEachIndexed { index, minute ->
            val next = run.getOrNull(index + 1)
            if (next == null || next.level != minute.level) {
                intervals += SensorSample(
                    SensorType.ACTIVITY_INTENSITY, segmentStart.level.code.toDouble(), segmentStart.start, minute.start.plus(ONE_MINUTE),
                )
                if (next != null) segmentStart = next
            }
        }
        return intervals
    }

    private companion object {
        val ONE_MINUTE: Duration = Duration.ofMinutes(1)
    }
}
