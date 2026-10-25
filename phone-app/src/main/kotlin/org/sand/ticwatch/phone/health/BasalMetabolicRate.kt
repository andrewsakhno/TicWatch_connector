package org.sand.ticwatch.phone.health

import java.time.LocalDate
import java.time.ZoneId

/** Body measurements a basal metabolic rate estimate needs. */
data class BodyProfile(val sex: BiologicalSex, val ageYears: Int, val heightCm: Double, val weightKg: Double)

/** Estimates basal metabolic rate (kcal/day); Strategy, so the formula is swappable (OCP). */
fun interface BasalMetabolicRateFormula {
    fun kilocaloriesPerDay(profile: BodyProfile): Double
}

/** Mifflin-St Jeor (1990): `10 * kg + 6.25 * cm - 5 * age + s`, s = +5 for men, -161 for women. */
class MifflinStJeorFormula : BasalMetabolicRateFormula {
    override fun kilocaloriesPerDay(profile: BodyProfile): Double =
        WEIGHT_FACTOR * profile.weightKg + HEIGHT_FACTOR * profile.heightCm - AGE_FACTOR * profile.ageYears +
            sexConstant(profile.sex)

    private fun sexConstant(sex: BiologicalSex): Double = when (sex) {
        BiologicalSex.MALE -> MALE_CONSTANT
        BiologicalSex.FEMALE -> FEMALE_CONSTANT
    }

    private companion object {
        const val WEIGHT_FACTOR = 10.0
        const val HEIGHT_FACTOR = 6.25
        const val AGE_FACTOR = 5.0
        const val MALE_CONSTANT = 5.0
        const val FEMALE_CONSTANT = -161.0
    }
}

/** Remembers the last basal metabolic rate emitted, so it is derived once per day (or on a profile change). */
interface DailyValueLog {
    suspend fun lastEmitted(): DailyValue?
    suspend fun record(value: DailyValue)
}

/** One value emitted for one local [day]. */
data class DailyValue(val day: LocalDate, val value: Double)

/**
 * One [SensorType.BASAL_METABOLIC_RATE] sample per local day, from the user profile via [formula].
 * Rides on the first push of each day, stamped at that push's first sample; a profile change during
 * the day re-emits it (the per-day client record id makes the new value replace the old one). Nothing
 * is derived until birth year, sex, height and weight are all set.
 */
class BasalMetabolicRateDeriver(
    private val profile: UserProfileStore,
    private val formula: BasalMetabolicRateFormula,
    private val log: DailyValueLog,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : SampleDeriver {

    override suspend fun derive(samples: List<SensorSample>): List<SensorSample> {
        val at = samples.minOfOrNull { it.recordedAt } ?: return emptyList()
        val day = at.atZone(zoneId).toLocalDate()
        val body = bodyProfile(day) ?: return emptyList()
        val bmr = formula.kilocaloriesPerDay(body)
        if (log.lastEmitted() == DailyValue(day, bmr)) return emptyList()
        log.record(DailyValue(day, bmr))
        return listOf(SensorSample(SensorType.BASAL_METABOLIC_RATE, bmr, at))
    }

    private suspend fun bodyProfile(day: LocalDate): BodyProfile? {
        val birthYear = profile.birthYear() ?: return null
        val sex = profile.sex() ?: return null
        val heightCm = profile.heightCm() ?: return null
        val weightKg = profile.weightKg() ?: return null
        return BodyProfile(sex, day.year - birthYear, heightCm, weightKg)
    }
}
