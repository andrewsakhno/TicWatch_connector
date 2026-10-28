package org.sand.ticwatch.phone.health

import java.time.Instant

/**
 * A single sensor sample synced from the watch, in a transport-neutral shape. When
 * [endedAt] is set it's an interval spanning [recordedAt]..[endedAt] (e.g. [SensorType.SLEEP]).
 * [source] names the device that measured it (stored per history row, put on the Health Connect record).
 * [snapshot] marks a reading of a Baseline Snapshot (all metrics taken together on request; history `is_snapshot`).
 */
data class SensorSample(
    val type: SensorType,
    val value: Double,
    val recordedAt: Instant,
    val endedAt: Instant? = null,
    val source: SampleSource = SampleSource.WATCH,
    val snapshot: Boolean = false,
)

/**
 * Measuring device of a sample; [code] is its persisted history value (explicit, never `ordinal`). WATCH also covers
 * everything the phone derives from watch data, including the wrist blood pressure estimate.
 */
enum class SampleSource(val code: Int, val label: String) {
    WATCH(0, "TicWatch"),
    BLOOD_PRESSURE_CUFF(1, "Bioland cuff"),

    /** The phone's own sensors (the microphone's snoring analysis). */
    PHONE(2, "Phone");

    companion object {
        fun of(code: Int): SampleSource = entries.firstOrNull { it.code == code } ?: WATCH
    }
}

/**
 * Wire contract with watch-app's `SensorType` (matched by name). CALORIES is active kcal,
 * CALORIES_DAILY the cumulative total kcal (basal included) since the day's start.
 * RESTING_HEART_RATE, SPEED (m/s), VO2_MAX (ml/kg/min) and BODY_TEMPERATURE (°C, from
 * SKIN_TEMPERATURE) are phone-derived. RESPIRATION_RATE is breaths/min. AMBIENT_TEMPERATURE (°C, the
 * watch's NST112 window mean), BAROMETRIC_PRESSURE (hPa) and AMBIENT_LIGHT (lux) have no Health
 * Connect record type and are kept in the history only. SLEEP_STAGE is an interval whose value is a
 * [SleepStageType.code]; it rides on the enclosing SLEEP session's record. STEPS_CADENCE (steps/min) and
 * BASAL_METABOLIC_RATE (kcal/day, one per local day from the user profile) are phone-derived.
 * From the watch's night breathing monitor: OXYGEN_DESATURATION (interval, SpO2 drop in points) and
 * SNORING (interval, fraction 0..1 of it snoring) - history only, like the phone-derived per-night
 * OXYGEN_DESATURATION_INDEX (events/hour) and SNORING_DURATION (minutes), both spanning the session.
 * Phone-derived for Health Connect: SKIN_TEMPERATURE_NIGHTLY (session interval, value = baseline °C;
 * the record's deltas are the night's SKIN_TEMPERATURE readings), BASAL_BODY_TEMPERATURE (°C),
 * POWER (W, running), ACTIVITY_INTENSITY (interval, value = [ActivityIntensityLevel.code]), WEIGHT (kg)
 * and HEIGHT (cm) from the profile, AUTO_EXERCISE (detected walk/run, value = Health Services exercise
 * type id like EXERCISE) and VO2_MAX_EXERCISE (ml/kg/min from a run's pace vs heart rate).
 * Phone-derived, history only: SLEEP_SCORE (session interval, 0..100), TRAINING_LOAD (workout interval,
 * Banister TRIMP) and HEART_RATE_ZONE_1..5 (workout interval, minutes in that Karvonen zone).
 * HEART_RATE_ALERT: a `mobvoi_hr_alert` event from the watch (value = its undecoded values[0]) - history only,
 * Health Connect has no record type for a heart-rate threshold alert.
 * BLOOD_PRESSURE_SYSTOLIC / BLOOD_PRESSURE_DIASTOLIC (mmHg): a calibrated cuffless estimate
 * ([BloodPressureEstimator]), saved as a pair with the same timestamp; the systolic sample's mapper
 * writes one `BloodPressureRecord` carrying both.
 */
enum class SensorType {
    HEART_RATE, STEPS, CALORIES, CALORIES_DAILY, RESTING_HEART_RATE, SLEEP, DISTANCE, FLOORS, ELEVATION_GAIN, EXERCISE,
    SPO2, HRV_RMSSD, SKIN_TEMPERATURE, SPEED, VO2_MAX, BODY_TEMPERATURE, AMBIENT_TEMPERATURE,
    RESPIRATION_RATE, BAROMETRIC_PRESSURE, AMBIENT_LIGHT, SLEEP_STAGE, STEPS_CADENCE, BASAL_METABOLIC_RATE,
    OXYGEN_DESATURATION, SNORING, OXYGEN_DESATURATION_INDEX, SNORING_DURATION,
    SKIN_TEMPERATURE_NIGHTLY, BASAL_BODY_TEMPERATURE, POWER, ACTIVITY_INTENSITY, WEIGHT, HEIGHT,
    AUTO_EXERCISE, VO2_MAX_EXERCISE, SLEEP_SCORE, TRAINING_LOAD,
    HEART_RATE_ZONE_1, HEART_RATE_ZONE_2, HEART_RATE_ZONE_3, HEART_RATE_ZONE_4, HEART_RATE_ZONE_5,
    HEART_RATE_ALERT, BLOOD_PRESSURE_SYSTOLIC, BLOOD_PRESSURE_DIASTOLIC,
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
