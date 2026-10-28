package org.sand.ticwatch.phone.health

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Biological sex as the metabolic formulas distinguish it; [key] is the stored value. */
enum class BiologicalSex(val key: String) {
    MALE("male"),
    FEMALE("female");

    companion object {
        fun fromKey(key: String?): BiologicalSex? = entries.firstOrNull { it.key == key }
    }
}

/**
 * User-entered profile data the profile-dependent estimates need ([Vo2MaxEstimator]: birth year;
 * [BasalMetabolicRateDeriver]: all of it). Null clears a stored value.
 */
interface UserProfileStore {
    suspend fun birthYear(): Int?
    suspend fun setBirthYear(year: Int?)
    suspend fun sex(): BiologicalSex?
    suspend fun setSex(sex: BiologicalSex?)
    suspend fun heightCm(): Double?
    suspend fun setHeightCm(heightCm: Double?)
    suspend fun weightKg(): Double?
    suspend fun setWeightKg(weightKg: Double?)
}

/**
 * A cuff reading the user typed in, with the wrist signals at that moment: the reference point
 * [BloodPressureEstimator] extrapolates from. RMSSD and skin temperature are null when the history had none.
 */
data class BloodPressureCalibration(
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Double,
    val rmssdMs: Double?,
    val skinTemperatureCelsius: Double?,
    val calibratedAt: Instant,
)

/** The patient's name for the doctor's report (ISP: kept apart from [UserProfileStore]); null/blank clears it. */
interface PatientNameStore {
    suspend fun patientName(): String?
    suspend fun setPatientName(name: String?)
}

/** Persists the single current [BloodPressureCalibration] (ISP: kept apart from [UserProfileStore]). */
interface BloodPressureCalibrationStore {
    suspend fun bloodPressureCalibration(): BloodPressureCalibration?
    suspend fun setBloodPressureCalibration(calibration: BloodPressureCalibration?)
}

private const val PREFS_NAME = "user_profile"
private const val BIRTH_YEAR_KEY = "birth_year"
private const val SEX_KEY = "sex"
private const val HEIGHT_CM_KEY = "height_cm"
private const val WEIGHT_KG_KEY = "weight_kg"
private const val PATIENT_NAME_KEY = "patient_name"
private const val BP_SYSTOLIC_KEY = "bp_baseline_systolic"
private const val BP_DIASTOLIC_KEY = "bp_baseline_diastolic"
private const val BP_HEART_RATE_KEY = "bp_calibrated_heart_rate"
private const val BP_RMSSD_KEY = "bp_calibrated_rmssd"
private const val BP_SKIN_TEMPERATURE_KEY = "bp_calibrated_skin_temperature"
private const val BP_CALIBRATED_AT_KEY = "bp_calibrated_at_epoch_ms"

/** [UserProfileStore] over SharedPreferences; every access hops to [Dispatchers.IO] (first read hits disk). */
class SharedPreferencesUserProfileStore(context: Context) : UserProfileStore, BloodPressureCalibrationStore, PatientNameStore {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun birthYear(): Int? = read { if (contains(BIRTH_YEAR_KEY)) getInt(BIRTH_YEAR_KEY, 0) else null }

    override suspend fun setBirthYear(year: Int?) = write { if (year == null) remove(BIRTH_YEAR_KEY) else putInt(BIRTH_YEAR_KEY, year) }

    override suspend fun sex(): BiologicalSex? = read { BiologicalSex.fromKey(getString(SEX_KEY, null)) }

    override suspend fun setSex(sex: BiologicalSex?) = write { if (sex == null) remove(SEX_KEY) else putString(SEX_KEY, sex.key) }

    override suspend fun heightCm(): Double? = readDouble(HEIGHT_CM_KEY)

    override suspend fun setHeightCm(heightCm: Double?) = writeDouble(HEIGHT_CM_KEY, heightCm)

    override suspend fun weightKg(): Double? = readDouble(WEIGHT_KG_KEY)

    override suspend fun setWeightKg(weightKg: Double?) = writeDouble(WEIGHT_KG_KEY, weightKg)

    override suspend fun patientName(): String? = read { getString(PATIENT_NAME_KEY, null) }

    override suspend fun setPatientName(name: String?) = write {
        val trimmed = name?.trim()
        if (trimmed.isNullOrEmpty()) remove(PATIENT_NAME_KEY) else putString(PATIENT_NAME_KEY, trimmed)
    }

    override suspend fun bloodPressureCalibration(): BloodPressureCalibration? = read {
        if (!contains(BP_CALIBRATED_AT_KEY)) return@read null
        BloodPressureCalibration(
            systolic = doubleOrNull(BP_SYSTOLIC_KEY) ?: return@read null,
            diastolic = doubleOrNull(BP_DIASTOLIC_KEY) ?: return@read null,
            heartRate = doubleOrNull(BP_HEART_RATE_KEY) ?: return@read null,
            rmssdMs = doubleOrNull(BP_RMSSD_KEY),
            skinTemperatureCelsius = doubleOrNull(BP_SKIN_TEMPERATURE_KEY),
            calibratedAt = Instant.ofEpochMilli(getLong(BP_CALIBRATED_AT_KEY, 0L)),
        )
    }

    override suspend fun setBloodPressureCalibration(calibration: BloodPressureCalibration?) = write {
        putDouble(BP_SYSTOLIC_KEY, calibration?.systolic)
        putDouble(BP_DIASTOLIC_KEY, calibration?.diastolic)
        putDouble(BP_HEART_RATE_KEY, calibration?.heartRate)
        putDouble(BP_RMSSD_KEY, calibration?.rmssdMs)
        putDouble(BP_SKIN_TEMPERATURE_KEY, calibration?.skinTemperatureCelsius)
        if (calibration == null) remove(BP_CALIBRATED_AT_KEY) else putLong(BP_CALIBRATED_AT_KEY, calibration.calibratedAt.toEpochMilli())
    }

    private fun SharedPreferences.doubleOrNull(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else null

    private fun SharedPreferences.Editor.putDouble(key: String, value: Double?) {
        if (value == null) remove(key) else putLong(key, value.toRawBits())
    }

    private suspend fun readDouble(key: String): Double? = read { doubleOrNull(key) }

    private suspend fun writeDouble(key: String, value: Double?) = write { putDouble(key, value) }

    private suspend fun <T> read(block: SharedPreferences.() -> T): T = withContext(Dispatchers.IO) { prefs.block() }

    private suspend fun write(block: SharedPreferences.Editor.() -> Unit) = withContext(Dispatchers.IO) {
        prefs.edit().apply(block).apply()
    }
}
