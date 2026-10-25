package org.sand.ticwatch.phone.history

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User-selectable transfer-history retention periods. */
enum class HistoryRetention(val days: Long) {
    THREE_DAYS(3),
    SEVEN_DAYS(7),
    FIFTEEN_DAYS(15),
    THIRTY_DAYS(30);

    companion object {
        val DEFAULT = SEVEN_DAYS
    }
}

/** User-selectable sleep-session retention periods; [months] null keeps sessions forever. */
enum class SleepSessionRetention(val months: Long?) {
    THREE_MONTHS(3),
    SIX_MONTHS(6),
    ONE_YEAR(12),
    TWO_YEARS(24),
    FOREVER(null);

    companion object {
        val DEFAULT = ONE_YEAR
    }
}

/** Persists one user setting. */
interface SettingStore<T> {
    suspend fun get(): T
    suspend fun set(value: T)
}

typealias HistoryRetentionStore = SettingStore<HistoryRetention>
typealias SleepSessionRetentionStore = SettingStore<SleepSessionRetention>

private const val PREFS_NAME = "sensor_history_settings"

/** Preference keys of the history settings; values are enum names. */
object HistorySettingKeys {
    const val RETENTION = "retention"
    const val SLEEP_SESSION_RETENTION = "sleep_session_retention"
}

/** Enum-valued [SettingStore] over SharedPreferences; every access hops to [Dispatchers.IO] (first read hits disk). */
class SharedPreferencesEnumSettingStore<E : Enum<E>>(
    context: Context,
    private val key: String,
    private val entries: List<E>,
    private val default: E,
) : SettingStore<E> {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun get(): E = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null)
        entries.firstOrNull { it.name == stored } ?: default
    }

    override suspend fun set(value: E) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, value.name).apply()
    }
}
