package org.sand.ticwatch.phone.history

import org.sand.ticwatch.phone.health.SensorType

/**
 * Stable integer codes [SensorType] is stored under in the history database. Explicit rather
 * than `ordinal`, so reordering or inserting enum entries never reinterprets stored rows;
 * a new [SensorType] must get a new, never-reused code here.
 */
internal object SensorTypeCodes {
    private val codes: Map<SensorType, Int> = mapOf(
        SensorType.HEART_RATE to 1,
        SensorType.STEPS to 2,
        SensorType.CALORIES to 3,
        SensorType.RESTING_HEART_RATE to 4,
        SensorType.SLEEP to 5,
        SensorType.DISTANCE to 6,
        SensorType.FLOORS to 7,
        SensorType.ELEVATION_GAIN to 8,
        SensorType.EXERCISE to 9,
        SensorType.SPO2 to 10,
        SensorType.HRV_RMSSD to 11,
        SensorType.SKIN_TEMPERATURE to 12,
        SensorType.CALORIES_DAILY to 13,
        SensorType.SPEED to 14,
        SensorType.VO2_MAX to 15,
        SensorType.BODY_TEMPERATURE to 16,
        SensorType.AMBIENT_TEMPERATURE to 17,
        SensorType.RESPIRATION_RATE to 18,
        SensorType.BAROMETRIC_PRESSURE to 19,
        SensorType.AMBIENT_LIGHT to 20,
        SensorType.SLEEP_STAGE to 21,
        SensorType.STEPS_CADENCE to 22,
        SensorType.BASAL_METABOLIC_RATE to 23,
    )

    private val types: Map<Int, SensorType> = codes.entries.associate { (type, code) -> code to type }

    init {
        check(codes.keys == SensorType.entries.toSet()) { "Every SensorType needs a storage code" }
        check(types.size == codes.size) { "Sensor type storage codes must be unique" }
    }

    fun code(type: SensorType): Int = codes.getValue(type)

    fun type(code: Int): SensorType = types.getValue(code)
}
