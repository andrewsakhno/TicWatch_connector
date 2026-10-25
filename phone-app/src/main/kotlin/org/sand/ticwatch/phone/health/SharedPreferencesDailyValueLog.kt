package org.sand.ticwatch.phone.health

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val BMR_PREFS_NAME = "basal_metabolic_rate_state"
private const val BMR_DAY_KEY = "day"
private const val BMR_VALUE_KEY = "kcal_per_day"

/** [DailyValueLog] over SharedPreferences, for [BasalMetabolicRateDeriver]. */
class SharedPreferencesDailyValueLog(context: Context, name: String = BMR_PREFS_NAME) : DailyValueLog {

    private val prefs by lazy { context.getSharedPreferences(name, Context.MODE_PRIVATE) }

    override suspend fun lastEmitted(): DailyValue? = withContext(Dispatchers.IO) {
        val day = prefs.getString(BMR_DAY_KEY, null) ?: return@withContext null
        DailyValue(LocalDate.parse(day), Double.fromBits(prefs.getLong(BMR_VALUE_KEY, 0L)))
    }

    override suspend fun record(value: DailyValue) = withContext(Dispatchers.IO) {
        prefs.edit().putString(BMR_DAY_KEY, value.day.toString()).putLong(BMR_VALUE_KEY, value.value.toRawBits()).apply()
    }
}
