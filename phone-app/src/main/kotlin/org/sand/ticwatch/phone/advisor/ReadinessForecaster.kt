package org.sand.ticwatch.phone.advisor

import org.sand.ticwatch.phone.spaceweather.KpSlot
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What the forecast needs beyond the current inputs: the hourly HR profile and NOAA's 3-hour Kp slots. */
data class ForecastContext(val heartRateNormByHour: Map<Int, Double>, val kpSlots: List<KpSlot>)

/**
 * Antihypertensive effect as a share of its peak by hours since the morning dose (nebivolol / perindopril order of
 * magnitude): ramps up over [ONSET_HOURS], plateaus until [PLATEAU_END_HOURS], then fades to [TROUGH_SHARE] at 24 h.
 */
class MedicationEffectCurve {

    fun share(hoursSinceDose: Double): Double = when {
        hoursSinceDose < 0 -> 0.0
        hoursSinceDose < ONSET_HOURS -> hoursSinceDose / ONSET_HOURS
        hoursSinceDose < PLATEAU_END_HOURS -> 1.0
        hoursSinceDose < DOSE_INTERVAL_HOURS ->
            1.0 - (1.0 - TROUGH_SHARE) * (hoursSinceDose - PLATEAU_END_HOURS) / (DOSE_INTERVAL_HOURS - PLATEAU_END_HOURS)
        else -> TROUGH_SHARE
    }

    private companion object {
        const val ONSET_HOURS = 2.0
        const val PLATEAU_END_HOURS = 8.0
        const val DOSE_INTERVAL_HOURS = 24.0
        const val TROUGH_SHARE = 0.5
    }
}

/**
 * Projects the inputs to a later hour and re-scores them with the same engine: the medication's additional effect
 * since now lowers diastolic and the HR excess, the morning surge raises diastolic, the HR norm and Kp follow the
 * hour. The HR excess over the norm and the other signals are carried over unchanged. Heuristic.
 */
class ReadinessForecaster(
    private val engine: CardioReadinessEngine,
    private val effectCurve: MedicationEffectCurve,
    private val zone: ZoneId,
    private val hours: Int = FORECAST_HOURS,
) {

    fun forecast(inputs: CardioReadinessInputs, context: ForecastContext): List<ReadinessForecastHour> {
        val firstHour = inputs.at.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))
        return (0 until hours).map { offset ->
            val start = firstHour.plus(Duration.ofHours(offset.toLong()))
            val projected = project(inputs, context, start)
            val assessment = engine.assess(projected)
            val localHour = start.atZone(zone).hour
            ReadinessForecastHour(
                start = start,
                score = assessment.score,
                state = assessment.state,
                walkWindow = localHour in WALK_HOURS && assessment.state == ReadinessState.OPTIMAL,
            )
        }
    }

    private fun project(inputs: CardioReadinessInputs, context: ForecastContext, at: Instant): CardioReadinessInputs {
        val relief = inputs.lastIntakeAt?.let { dose ->
            (effectCurve.share(hoursBetween(dose, at)) - effectCurve.share(hoursBetween(dose, inputs.at))).coerceAtLeast(0.0)
        } ?: 0.0
        val localHour = at.atZone(zone).hour
        val surge = if (localHour in MORNING_SURGE_HOURS) MORNING_SURGE_MMHG else 0.0
        val norm = context.heartRateNormByHour[localHour]
        val heartRate = inputs.heartRateBpm?.let { current ->
            val excess = inputs.heartRateNormBpm?.let { current - it } ?: 0.0
            (norm ?: inputs.heartRateNormBpm ?: current) + excess - relief * HEART_RATE_RELIEF_BPM
        }
        return inputs.copy(
            at = at,
            heartRateBpm = heartRate,
            heartRateNormBpm = norm ?: inputs.heartRateNormBpm,
            diastolic = inputs.diastolic?.let { it - relief * DIASTOLIC_RELIEF_MMHG + surge },
            // Keeps the reading's current age: a projected value stands in for it, so it must not age out mid-forecast.
            pressureMeasuredAt = inputs.pressureMeasuredAt?.plus(Duration.between(inputs.at, at)),
            kp = context.kpSlots.kpAt(at) ?: inputs.kp,
        )
    }

    private fun hoursBetween(from: Instant, to: Instant): Double = Duration.between(from, to).toMinutes() / MINUTES_PER_HOUR

    private companion object {
        const val FORECAST_HOURS = 12
        const val MINUTES_PER_HOUR = 60.0
        const val DIASTOLIC_RELIEF_MMHG = 8.0
        const val HEART_RATE_RELIEF_BPM = 8.0
        const val MORNING_SURGE_MMHG = 5.0
        val MORNING_SURGE_HOURS = 6..9
        val WALK_HOURS = 7..20
    }
}

private val KP_SLOT: Duration = Duration.ofHours(3)

/** Kp of the 3-hour slot covering [at], null outside NOAA's range. */
internal fun List<KpSlot>.kpAt(at: Instant): Double? = lastOrNull { !it.start.isAfter(at) && at.isBefore(it.start.plus(KP_SLOT)) }?.kp
