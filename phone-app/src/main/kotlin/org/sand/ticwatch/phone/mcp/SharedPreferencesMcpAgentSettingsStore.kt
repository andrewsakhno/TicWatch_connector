package org.sand.ticwatch.phone.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

private const val PREFS_NAME = "mcp_agent_settings"
private const val ENABLED_KEY = "enabled"
private const val TOKEN_KEY = "token"
private const val PORT_KEY = "port"
private const val DEFAULT_PORT = 8765
private const val TOKEN_BYTES = 24

/** [McpAgentSettingsStore] over plain SharedPreferences; every access hops to [Dispatchers.IO] (first read hits disk). */
class SharedPreferencesMcpAgentSettingsStore(context: Context) : McpAgentSettingsStore {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override suspend fun isEnabled(): Boolean = withContext(Dispatchers.IO) { prefs.getBoolean(ENABLED_KEY, false) }

    override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    override suspend fun getToken(): String = withContext(Dispatchers.IO) {
        prefs.getString(TOKEN_KEY, null) ?: regenerateToken()
    }

    override suspend fun regenerateToken(): String = withContext(Dispatchers.IO) {
        newToken().also { prefs.edit().putString(TOKEN_KEY, it).apply() }
    }

    override suspend fun getPort(): Int = withContext(Dispatchers.IO) { prefs.getInt(PORT_KEY, DEFAULT_PORT) }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
