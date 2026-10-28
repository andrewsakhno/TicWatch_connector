package org.sand.ticwatch.watch.sensors.adaptive

import java.time.Duration

/**
 * How closely a [MeasurementGroup] is watched. IDLE is the battery-saving base cadence; ALERT the boosted one a
 * trigger asked for after an out-of-range reading; RECOVERY the decay phase after readings returned to normal,
 * which drops back to IDLE only after a stretch of stable normal readings (hysteresis).
 */
enum class MonitoringProfile(val severity: Int) {
    IDLE(0),
    RECOVERY(1),
    ALERT(2),
}

/** Sensors that are scheduled together: one periodic window group, or the heart-rate stream. */
enum class MeasurementGroup {
    /** Passive Health Services heart rate in IDLE; the live Measure API stream while boosted. */
    HEART_RATE,
    SPO2,

    /** RMSSD and breathing rate, both from the RR-interval series. */
    HRV,
    SKIN_TEMPERATURE,
    ENVIRONMENT,
}

/** A trigger's demand: measure [group] every [interval] while it is abnormal; [CONTINUOUS] keeps it streaming. */
data class CadenceBoost(val group: MeasurementGroup, val interval: Duration) {
    companion object {
        val CONTINUOUS: Duration = Duration.ZERO
    }
}
