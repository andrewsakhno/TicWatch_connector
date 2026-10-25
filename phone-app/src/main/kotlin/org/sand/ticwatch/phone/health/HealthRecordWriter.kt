package org.sand.ticwatch.phone.health

import java.time.Instant

/**
 * A single sensor sample synced from the watch, in a transport-neutral shape. When
 * [endedAt] is set it's an interval spanning [recordedAt]..[endedAt] (e.g. [SensorType.SLEEP]).
 */
data class SensorSample(
    val type: SensorType,
    val value: Double,
    val recordedAt: Instant,
    val endedAt: Instant? = null,
)

/**
 * Wire contract with watch-app's `SensorType` (matched by name). CALORIES is active kcal,
 * CALORIES_DAILY the cumulative total kcal (basal included) since the day's start.
 * RESTING_HEART_RATE, SPEED (m/s), VO2_MAX (ml/kg/min) and BODY_TEMPERATURE (°C, from
 * SKIN_TEMPERATURE) are phone-derived. RESPIRATION_RATE is breaths/min. AMBIENT_TEMPERATURE (°C, the
 * watch's NST112 window mean), BAROMETRIC_PRESSURE (hPa) and AMBIENT_LIGHT (lux) have no Health
 * Connect record type and are kept in the history only. SLEEP_STAGE is an interval whose value is a
 * [SleepStageType.code]; it rides on the enclosing SLEEP session's record. STEPS_CADENCE (steps/min) and
 * BASAL_METABOLIC_RATE (kcal/day, one per local day from the user profile) are phone-derived.
 */
enum class SensorType {
    HEART_RATE, STEPS, CALORIES, CALORIES_DAILY, RESTING_HEART_RATE, SLEEP, DISTANCE, FLOORS, ELEVATION_GAIN, EXERCISE,
    SPO2, HRV_RMSSD, SKIN_TEMPERATURE, SPEED, VO2_MAX, BODY_TEMPERATURE, AMBIENT_TEMPERATURE,
    RESPIRATION_RATE, BAROMETRIC_PRESSURE, AMBIENT_LIGHT, SLEEP_STAGE, STEPS_CADENCE, BASAL_METABOLIC_RATE,
}

/**
 * Writes watch-sourced sensor samples into Google Health. Abstracted behind an
 * interface (DIP/OCP) so the Health Connect SDK dependency stays isolated to
 * [HealthConnectRecordWriter], and so sync logic can be unit-tested against a fake.
 */
interface HealthRecordWriter {
    val requiredPermissions: Set<String>
    suspend fun hasRequiredPermissions(): Boolean
    suspend fun write(samples: List<SensorSample>): Result<Unit>
}
