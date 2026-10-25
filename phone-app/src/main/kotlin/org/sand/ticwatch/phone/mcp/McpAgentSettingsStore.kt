package org.sand.ticwatch.phone.mcp

/** Persists the MCP agent switch and the credentials/port external clients must use to reach it. */
interface McpAgentSettingsStore {
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)

    /** Bearer token required on every request; generated on first read and stable until [regenerateToken]. */
    suspend fun getToken(): String
    suspend fun regenerateToken(): String

    suspend fun getPort(): Int
}
