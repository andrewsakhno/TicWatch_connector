package ticwatch

import "fmt"

// sensorTypeNames mirrors the phone's SensorTypeCodes (history/SensorTypeCodes.kt) for display only; the API stays
// agnostic of the codes' meaning. Keep it in sync when the phone adds a type.
var sensorTypeNames = map[int32]string{
	1: "HEART_RATE", 2: "STEPS", 3: "CALORIES", 4: "RESTING_HEART_RATE", 5: "SLEEP", 6: "DISTANCE", 7: "FLOORS",
	8: "ELEVATION_GAIN", 9: "EXERCISE", 10: "SPO2", 11: "HRV_RMSSD", 12: "SKIN_TEMPERATURE", 13: "CALORIES_DAILY",
	14: "SPEED", 15: "VO2_MAX", 16: "BODY_TEMPERATURE", 17: "AMBIENT_TEMPERATURE", 18: "RESPIRATION_RATE",
	19: "BAROMETRIC_PRESSURE", 20: "AMBIENT_LIGHT", 21: "SLEEP_STAGE", 22: "STEPS_CADENCE", 23: "BASAL_METABOLIC_RATE",
	24: "OXYGEN_DESATURATION", 25: "SNORING", 26: "OXYGEN_DESATURATION_INDEX", 27: "SNORING_DURATION",
	28: "SKIN_TEMPERATURE_NIGHTLY", 29: "BASAL_BODY_TEMPERATURE", 30: "POWER", 31: "ACTIVITY_INTENSITY", 32: "WEIGHT",
	33: "HEIGHT", 34: "AUTO_EXERCISE", 35: "VO2_MAX_EXERCISE", 36: "SLEEP_SCORE", 37: "TRAINING_LOAD",
	38: "HEART_RATE_ZONE_1", 39: "HEART_RATE_ZONE_2", 40: "HEART_RATE_ZONE_3", 41: "HEART_RATE_ZONE_4",
	42: "HEART_RATE_ZONE_5", 43: "HEART_RATE_ALERT", 44: "BLOOD_PRESSURE_SYSTOLIC", 45: "BLOOD_PRESSURE_DIASTOLIC",
	46: "PULSE_AMPLITUDE_DROP", 47: "FATIGUE_INDEX", 48: "PULSE_WAVE_SYSTOLIC", 49: "PULSE_WAVE_DIASTOLIC",
	50: "HEART_RATE_ZONE_0",
}

const unknownSensorTypeFormat = "type %d"

func sensorTypeName(code int32) string {
	if name, ok := sensorTypeNames[code]; ok {
		return name
	}
	return fmt.Sprintf(unknownSensorTypeFormat, code)
}
