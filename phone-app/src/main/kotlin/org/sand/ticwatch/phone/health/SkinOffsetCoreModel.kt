package org.sand.ticwatch.phone.health

import java.time.Instant

/** The user's own wrist skin temperature during sleep, from earlier nights. */
fun interface SkinBaselineSource {
    /** Typical sleeping skin temperature over nights ending before [before], or null without enough nights. */
    suspend fun nightlyBaseline(before: Instant): Double?
}

/**
 * Skin-only fallback: core temperature is tightly regulated while wrist skin swings with ambient
 * and blood flow, so the skin deviation from a reference is damped by [gain] around a setpoint.
 * Awake, the reference is a generic thermoneutral wrist ([skinReference] around [coreSetpoint]).
 * Asleep - the one stretch where ambient, posture and activity are about constant - it is the
 * user's own nightly skin baseline from [nightlyBaseline] around the lower
 * [sleepingCoreSetpoint], which calibrates out individual fit and skin differences.
 */
class SkinOffsetCoreModel(
    private val nightlyBaseline: SkinBaselineSource,
    private val coreSetpoint: Double = 36.8,
    private val sleepingCoreSetpoint: Double = 36.5,
    private val skinReference: Double = 33.5,
    private val gain: Double = 0.3,
) : CoreTemperatureModel {

    override suspend fun coreCelsius(skin: SensorSample, context: List<SensorSample>): Double {
        val baseline = if (isAsleep(skin, context)) nightlyBaseline.nightlyBaseline(skin.recordedAt) else null
        return if (baseline != null) sleepingCoreSetpoint + gain * (skin.value - baseline)
        else coreSetpoint + gain * (skin.value - skinReference)
    }

    private fun isAsleep(skin: SensorSample, context: List<SensorSample>): Boolean {
        val at = skin.endedAt ?: skin.recordedAt
        return context.any { it.type == SensorType.SLEEP && at in TimeSpan.of(it) }
    }
}
