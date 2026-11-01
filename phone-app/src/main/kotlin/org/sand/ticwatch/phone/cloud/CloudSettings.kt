package org.sand.ticwatch.phone.cloud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * User-facing cloud settings. [manualHost] (IP or host name, optional `:port`) is tried before the subnet
 * scan - for a server outside the phone's /24 (e.g. a Tailscale address). [apiToken] is sent as a Bearer
 * token when set; the local server doesn't require one yet.
 */
data class CloudSettings(
    val enabled: Boolean = true,
    val manualHost: String = "",
    val apiToken: String = "",
)

interface CloudSettingsStore {
    suspend fun get(): CloudSettings
    suspend fun set(settings: CloudSettings)
}

/** What discovery remembers between syncs, plus this install's stable id sent with every upload. */
interface CloudConnectionMemory {
    /** Host that last answered as the cloud server, tried first; null = rescan. */
    var lastHost: String?
    val deviceId: String
}

/** Both over one SharedPreferences file, one per process. First access reads the file - call off the main thread. */
class SharedPreferencesCloudSettings private constructor(private val prefs: SharedPreferences) :
    CloudSettingsStore, CloudConnectionMemory {

    override suspend fun get(): CloudSettings = withContext(Dispatchers.IO) {
        CloudSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            manualHost = prefs.getString(KEY_MANUAL_HOST, null).orEmpty(),
            apiToken = prefs.getString(KEY_API_TOKEN, null).orEmpty(),
        )
    }

    override suspend fun set(settings: CloudSettings) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_MANUAL_HOST, settings.manualHost.trim())
            .putString(KEY_API_TOKEN, settings.apiToken.trim())
            .apply()
    }

    override var lastHost: String?
        get() = prefs.getString(KEY_LAST_HOST, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_HOST, value).apply()
        }

    override val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
        }

    /** Synchronous token read for the HTTP interceptor (already on an OkHttp thread). */
    fun apiTokenNow(): String = prefs.getString(KEY_API_TOKEN, null).orEmpty()

    companion object {
        private const val PREFS_NAME = "cloud_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MANUAL_HOST = "manual_host"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_DEVICE_ID = "device_id"

        @Volatile private var instance: SharedPreferencesCloudSettings? = null

        fun get(context: Context): SharedPreferencesCloudSettings = instance ?: synchronized(this) {
            instance ?: SharedPreferencesCloudSettings(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}

/**
 * While cloud storage is on, rows not yet uploaded stay until the server acknowledges them, however long it is
 * away; with cloud storage off nothing would ever upload them, so the retention applies to them too.
 */
class SettingsCloudRetentionHold(private val settings: CloudSettingsStore) : CloudRetentionHold {
    override suspend fun keepsUnsynced(): Boolean = settings.get().enabled
}
