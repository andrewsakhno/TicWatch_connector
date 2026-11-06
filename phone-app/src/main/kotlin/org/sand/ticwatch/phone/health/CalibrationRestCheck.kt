package org.sand.ticwatch.phone.health

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Why an automatic calibration was refused: the wrist signals at the cuff reading don't describe rest. */
sealed interface CalibrationRestViolation {
    val heartRate: Double

    /** Pulse more than [CalibrationRestLimits.MAX_ABOVE_RESTING_BPM] above the day's resting heart rate. */
    data class AboveResting(override val heartRate: Double, val restingHeartRate: Double) : CalibrationRestViolation

    /** Pulse still moving (recovery from a walk, stress), [bpmPerMinute] being the trend's steepness. */
    data class Unsteady(override val heartRate: Double, val bpmPerMinute: Double) : CalibrationRestViolation
}

/** Limits of [WristCalibrationRestCheck]. */
object CalibrationRestLimits {
    const val MAX_ABOVE_RESTING_BPM = 12.0
    const val MAX_BPM_PER_MINUTE = 6.0
    val GRADIENT_WINDOW: Duration = Duration.ofMinutes(3)

    /** Fewer readings or a shorter span leaves the trend unknown, which doesn't refuse the calibration. */
    const val MIN_GRADIENT_READINGS = 3
    val MIN_GRADIENT_SPAN: Duration = Duration.ofMinutes(1)
}

/** Decides whether a heart rate at a cuff reading is calm enough to calibrate against; null = accepted. */
fun interface CalibrationRestCheck {
    suspend fun violation(heartRate: Double, at: Instant): CalibrationRestViolation?
}

/**
 * [CalibrationRestCheck] over the stored watch signals: refuses a pulse above the resting heart rate +
 * [CalibrationRestLimits.MAX_ABOVE_RESTING_BPM], or a least-squares heart-rate slope over the
 * [CalibrationRestLimits.GRADIENT_WINDOW] before the reading steeper than [CalibrationRestLimits.MAX_BPM_PER_MINUTE].
 * A calibration taken in such a moment pairs a raised pressure with a transient pulse and drags every later estimate.
 */
class WristCalibrationRestCheck(private val signals: WristSignalsReader) : CalibrationRestCheck {

    override suspend fun violation(heartRate: Double, at: Instant): CalibrationRestViolation? {
        signals.restingHeartRate(at)
            ?.takeIf { heartRate > it + CalibrationRestLimits.MAX_ABOVE_RESTING_BPM }
            ?.let { return CalibrationRestViolation.AboveResting(heartRate, it) }
        val window = signals.heartRates(at.minus(CalibrationRestLimits.GRADIENT_WINDOW), at.plusMillis(1))
        val readings = if (window.any { it.at == at }) window else window + HeartRateReading(heartRate, at)
        val slope = HeartRateTrend.bpmPerMinute(readings) ?: return null
        return if (abs(slope) > CalibrationRestLimits.MAX_BPM_PER_MINUTE) CalibrationRestViolation.Unsteady(heartRate, slope) else null
    }
}

/** Least-squares slope of heart rate over time. */
object HeartRateTrend {
    private const val MILLIS_PER_MINUTE = 60_000.0

    /** bpm per minute, or null with fewer than [CalibrationRestLimits.MIN_GRADIENT_READINGS] readings or too short a span. */
    fun bpmPerMinute(readings: List<HeartRateReading>): Double? {
        if (readings.size < CalibrationRestLimits.MIN_GRADIENT_READINGS) return null
        val origin = readings.minOf { it.at }
        if (Duration.between(origin, readings.maxOf { it.at }) < CalibrationRestLimits.MIN_GRADIENT_SPAN) return null
        val minutes = readings.map { (it.at.toEpochMilli() - origin.toEpochMilli()) / MILLIS_PER_MINUTE }
        val meanX = minutes.average()
        val meanY = readings.map { it.bpm }.average()
        val covariance = minutes.indices.sumOf { (minutes[it] - meanX) * (readings[it].bpm - meanY) }
        val variance = minutes.sumOf { (it - meanX) * (it - meanX) }
        return if (variance == 0.0) null else covariance / variance
    }
}

/** Lowest heart rate of the personal resting corridors at a moment; null without one. */
fun interface RestingHeartRateFloor {
    suspend fun lowBpm(at: Instant): Double?
}
