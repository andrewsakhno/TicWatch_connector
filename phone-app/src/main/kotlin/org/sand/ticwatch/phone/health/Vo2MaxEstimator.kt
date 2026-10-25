package org.sand.ticwatch.phone.health

import java.time.ZoneId

/**
 * VO2 max by the Uth-Sorensen heart-rate ratio, `15.3 * HRmax / HRrest` - the method
 * Health Connect names `MEASUREMENT_METHOD_HEART_RATE_RATIO`. HRmax is age-predicted
 * (Tanaka: `208 - 0.7 * age`), so nothing is derived until the user's birth year is set;
 * HRrest is each [SensorType.RESTING_HEART_RATE] improvement derived earlier in the chain,
 * so the daily VO2 max record follows that day's resting HR.
 */
class Vo2MaxEstimator(
    private val profile: UserProfileStore,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val restingHeartRates = samples.filter { it.type == SensorType.RESTING_HEART_RATE && it.value > 0 }
        if (restingHeartRates.isEmpty()) return emptyList()
        val birthYear = profile.birthYear() ?: return emptyList()
        return restingHeartRates.map { resting ->
            val age = resting.recordedAt.atZone(zoneId).year - birthYear
            val maxHeartRate = TANAKA_INTERCEPT - TANAKA_AGE_SLOPE * age
            SensorSample(SensorType.VO2_MAX, UTH_FACTOR * maxHeartRate / resting.value, resting.recordedAt)
        }
    }

    private companion object {
        const val UTH_FACTOR = 15.3
        const val TANAKA_INTERCEPT = 208.0
        const val TANAKA_AGE_SLOPE = 0.7
    }
}
