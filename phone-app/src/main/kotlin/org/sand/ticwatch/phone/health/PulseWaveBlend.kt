package org.sand.ticwatch.phone.health

import java.time.Instant

/** A personal pulse-wave blood pressure in mmHg with its model's leave-one-out systolic error. */
data class PulseWaveBloodPressure(val systolic: Double, val diastolic: Double, val systolicErrorMmHg: Double)

/** The pulse-wave blood pressure of a strip recorded shortly before a moment; null without one. */
fun interface RecentPulseWaveBloodPressure {
    suspend fun near(at: Instant): PulseWaveBloodPressure?
}

/**
 * Inverse-variance blend of a wrist-signal estimate with a pulse-wave one: each is weighted by `1 / error²`, the
 * wrist model's error being the fixed [wristErrorMmHg] and the pulse-wave model's its own leave-one-out error.
 * Pulse morphology (half width, perfusion) tracks vascular resistance that heart rate and RMSSD miss.
 */
class PulseWaveBlender(private val wristErrorMmHg: Double = WRIST_MODEL_ERROR_MMHG) {

    fun blend(wrist: BloodPressureReading, pulse: PulseWaveBloodPressure): BloodPressureReading {
        val pulseError = pulse.systolicErrorMmHg.coerceAtLeast(MIN_ERROR_MMHG)
        val pulseWeight = wristErrorMmHg * wristErrorMmHg / (wristErrorMmHg * wristErrorMmHg + pulseError * pulseError)
        return wrist.copy(
            systolic = mix(wrist.systolic, pulse.systolic, pulseWeight),
            diastolic = mix(wrist.diastolic, pulse.diastolic, pulseWeight),
        )
    }

    private fun mix(wrist: Double, pulse: Double, pulseWeight: Double): Double = wrist + (pulse - wrist) * pulseWeight

    companion object {
        /** Typical systolic error of the wrist-signal model against the cuff. */
        const val WRIST_MODEL_ERROR_MMHG = 10.0

        /** Floor of the pulse-wave error, so a lucky leave-one-out fit can't take the whole weight. */
        const val MIN_ERROR_MMHG = 3.0
    }
}
