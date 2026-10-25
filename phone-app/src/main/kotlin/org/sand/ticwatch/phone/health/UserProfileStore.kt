package org.sand.ticwatch.phone.health

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

private const val PREFS_NAME = "user_profile"
private const val BIRTH_YEAR_KEY = "birth_year"
private const val SEX_KEY = "sex"
private const val HEIGHT_CM_KEY = "height_cm"
private const val WEIGHT_KG_KEY = "weight_kg"

/** [UserProfileStore] over SharedPreferences; every access hops to [Dispatchers.IO] (first read hits disk). */
class SharedPreferencesUserProfileStore(context: Context) : UserProfileStore {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun birthYear(): Int? = read { if (contains(BIRTH_YEAR_KEY)) getInt(BIRTH_YEAR_KEY, 0) else null }

    override suspend fun setBirthYear(year: Int?) = write { if (year == null) remove(BIRTH_YEAR_KEY) else putInt(BIRTH_YEAR_KEY, year) }

    override suspend fun sex(): BiologicalSex? = read { BiologicalSex.fromKey(getString(SEX_KEY, null)) }

    override suspend fun setSex(sex: BiologicalSex?) = write { if (sex == null) remove(SEX_KEY) else putString(SEX_KEY, sex.key) }

    override suspend fun heightCm(): Double? = readDouble(HEIGHT_CM_KEY)

    override suspend fun setHeightCm(heightCm: Double?) = writeDouble(HEIGHT_CM_KEY, heightCm)

    override suspend fun weightKg(): Double? = readDouble(WEIGHT_KG_KEY)

    override suspend fun setWeightKg(weightKg: Double?) = writeDouble(WEIGHT_KG_KEY, weightKg)

    private suspend fun readDouble(key: String): Double? = read { if (contains(key)) Double.fromBits(getLong(key, 0L)) else null }

    private suspend fun writeDouble(key: String, value: Double?) = write { if (value == null) remove(key) else putLong(key, value.toRawBits()) }

    private suspend fun <T> read(block: SharedPreferences.() -> T): T = withContext(Dispatchers.IO) { prefs.block() }

    private suspend fun write(block: SharedPreferences.Editor.() -> Unit) = withContext(Dispatchers.IO) {
        prefs.edit().apply(block).apply()
    }
}
