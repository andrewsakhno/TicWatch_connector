package org.sand.ticwatch.phone.health

import java.time.Instant
import java.time.ZoneId

/** The highest bpm still inside a heart-rate zone at an instant, or null while the profile can't tell. */
fun interface HeartRateZoneCeilingSource {
    suspend fun ceilingBpm(at: Instant): Double?
}

/**
 * Top of Karvonen [zone] (where the next zone starts): resting HR from [restingHeartRate] plus the next zone's share
 * of the reserve up to the age-predicted [TanakaMaxHeartRate]. The top zone has no ceiling. Needs the birth year.
 */
class KarvonenZoneCeiling(
    private val zone: HeartRateZone,
    private val profile: UserProfileStore,
    private val restingHeartRate: RestingHeartRateBaseline,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : HeartRateZoneCeilingSource {

    override suspend fun ceilingBpm(at: Instant): Double? {
        val next = HeartRateZone.entries.getOrNull(zone.ordinal + 1) ?: return null
        val birthYear = profile.birthYear() ?: return null
        val resting = restingHeartRate.bpm(at, emptyList()) ?: return null
        val max = TanakaMaxHeartRate.bpm(at.atZone(zoneId).year - birthYear)
        if (max <= resting) return null
        return resting + next.minReserveFraction * (max - resting)
    }
}
