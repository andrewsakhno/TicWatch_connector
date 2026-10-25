package org.sand.ticwatch.watch.sensors

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * A point reading, or - when [endedAt] is set - an interval reading spanning
 * [recordedAt]..[endedAt] (e.g. a [SensorType.SLEEP] session).
 */
data class SensorSample(
    val type: SensorType,
    val value: Double,
    val recordedAt: Instant,
    val endedAt: Instant? = null,
)

/**
 * Wire contract with phone-app's `SensorType` (matched by name). Interval types carry
 * their delta over [SensorSample.recordedAt]..[SensorSample.endedAt]: STEPS (count),
 * DISTANCE / ELEVATION_GAIN (meters), FLOORS, CALORIES (active kcal - this watch's passive
 * CALORIES excludes the basal rate); CALORIES_DAILY is the cumulative total kcal (basal
 * included) since the start of the day. SLEEP and EXERCISE are sessions, EXERCISE's value
 * being the Health Services `ExerciseType.id`. SLEEP_STAGE is an interval whose value is a
 * [SleepStage.code]. STEPS_CADENCE is phone-derived; listed so both enums name the same types.
 */
enum class SensorType {
    HEART_RATE, STEPS, CALORIES, CALORIES_DAILY, SLEEP, DISTANCE, FLOORS, ELEVATION_GAIN, EXERCISE,

    // Point readings from the periodic Mobvoi vendor-sensor window: SpO2 (%), HRV RMSSD (ms),
    // SKIN_TEMPERATURE an interval mean (°C). AMBIENT_TEMPERATURE: the standard NST112 case
    // sensor's interval mean (°C) over the same window. RESPIRATION_RATE (breaths/min) is the
    // `psp_rr` window median. BAROMETRIC_PRESSURE (hPa, `mspc110`) and AMBIENT_LIGHT (lux,
    // `opt3007`) are time-weighted interval means.
    SPO2, HRV_RMSSD, SKIN_TEMPERATURE, AMBIENT_TEMPERATURE, RESPIRATION_RATE, BAROMETRIC_PRESSURE, AMBIENT_LIGHT,

    SLEEP_STAGE, STEPS_CADENCE,
}

/**
 * Streams on-watch sensor readings. Abstracted (DIP/ISP) so
 * [org.sand.ticwatch.watch.datalayer.SensorSyncSender] doesn't depend on the Health
 * Services SDK directly, keeping that dependency swappable/testable.
 */
interface SensorCollector {
    fun heartRateSamples(): Flow<SensorSample>
}
