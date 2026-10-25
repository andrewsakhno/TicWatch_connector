package org.sand.ticwatch.phone.health

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos

/**
 * Heat-balance model: at steady state the heat flowing core -> skin equals the heat flowing
 * skin -> ambient, so `T_core = T_skin + k_ambient * (T_skin - T_ambient)`, [ambientCoupling]
 * being the tissue/air thermal resistance ratio. Two corrections follow: a heart-rate term
 * (fever raises the resting HR by roughly 10 bpm per °C - Liebermeister's rule - so each bpm
 * above the [restingBaseline], past [heartRateDeadband] and capped at [maxHeartRateElevation],
 * adds [heartRateGain]) and the [circadian] rhythm of the core. Activity is not judged here:
 * the estimator's qualifiers drop readings taken while moving, so a raised HR left over is a
 * resting one.
 *
 * Needs an AMBIENT_TEMPERATURE window within [maxAmbientGap] of the skin window and a gradient
 * of at least [minGradient] (in heat at or above skin temperature the flux model breaks down);
 * otherwise returns null for the fallback model. The ambient sensor sits in the watch case, so
 * [ambientCoupling] is calibrated against that reading, not free-air temperature.
 */
class DualHeatFluxCoreModel(
    private val restingBaseline: RestingHeartRateBaseline,
    private val circadian: CircadianOffset,
    private val ambientCoupling: Double = 0.27,
    private val heartRateGain: Double = 0.1,
    private val heartRateDeadband: Double = 5.0,
    private val maxHeartRateElevation: Double = 30.0,
    private val heartRateLookback: Duration = Duration.ofMinutes(5),
    private val minGradient: Double = 0.5,
    private val maxAmbientGap: Duration = Duration.ofMinutes(10),
) : CoreTemperatureModel {

    override suspend fun coreCelsius(skin: SensorSample, context: List<SensorSample>): Double? {
        val ambient = ambientNear(skin, context) ?: return null
        val gradient = skin.value - ambient
        if (gradient < minGradient) return null
        val at = skin.endedAt ?: skin.recordedAt
        return skin.value + ambientCoupling * gradient + heartRateTerm(skin, at, context) + circadian.at(at)
    }

    private fun ambientNear(skin: SensorSample, context: List<SensorSample>): Double? {
        val window = TimeSpan.of(skin)
        val reach = TimeSpan(window.start.minus(maxAmbientGap), window.end.plus(maxAmbientGap))
        return context
            .filter { it.type == SensorType.AMBIENT_TEMPERATURE && TimeSpan.of(it).overlap(reach) > Duration.ZERO }
            .minByOrNull { Duration.between(it.recordedAt, skin.recordedAt).abs() }
            ?.value
    }

    private fun heartRateTerm(skin: SensorSample, at: Instant, context: List<SensorSample>): Double {
        val bpm = meanHeartRate(skin, context, heartRateLookback) ?: return NO_CORRECTION
        val resting = restingBaseline.bpm(at, context) ?: return NO_CORRECTION
        return heartRateGain * (bpm - resting - heartRateDeadband).coerceIn(NO_CORRECTION, maxHeartRateElevation)
    }

    private companion object {
        const val NO_CORRECTION = 0.0
    }
}

/**
 * Circadian swing of the core temperature: a cosine of [amplitude] °C peaking at local [peak]
 * (late afternoon), its trough twelve hours later, before waking.
 */
class CircadianOffset(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val amplitude: Double = 0.25,
    private val peak: LocalTime = LocalTime.of(17, 0),
) {
    fun at(instant: Instant): Double {
        val hoursFromPeak = Duration.between(peak, instant.atZone(zoneId).toLocalTime()).toMinutes() / MINUTES_PER_HOUR
        return amplitude * cos(2 * PI * hoursFromPeak / HOURS_PER_DAY)
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
        const val HOURS_PER_DAY = 24.0
    }
}
