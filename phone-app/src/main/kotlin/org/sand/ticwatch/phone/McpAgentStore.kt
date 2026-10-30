package org.sand.ticwatch.phone

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.sand.ticwatch.phone.mcp.McpAgentSettingsStore
import org.sand.ticwatch.phone.mcp.McpAgentState
import org.sand.ticwatch.phone.ui.UiStateStore

/** Starts or stops the MCP agent service. */
fun interface McpAgentControl {
    fun setRunning(running: Boolean)
}

/** The MCP agent screen: the switch, the service's own state, the token and where the server is reachable. */
data class McpAgentUiState(
    val enabled: Boolean = false,
    val token: String = "",
    val port: Int = 0,
    val service: McpAgentState? = null,
    val addresses: List<String> = emptyList(),
)

/** State holder of the MCP agent screen; follows the service's [serviceStates] and reads settings off the main thread. */
class McpAgentStore(
    private val settings: McpAgentSettingsStore,
    serviceStates: StateFlow<McpAgentState>,
    private val control: McpAgentControl,
    private val addresses: () -> List<String>,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : UiStateStore<McpAgentUiState>(McpAgentUiState(), scope, ioDispatcher) {

    init {
        launchIo({ Triple(settings.isEnabled(), settings.getToken(), settings.getPort()) }) { result ->
            result.onSuccess { (enabled, token, port) -> setState { it.copy(enabled = enabled, token = token, port = port) } }
        }
        observe(serviceStates, ::onServiceState)
    }

    fun setEnabled(on: Boolean) {
        setState { it.copy(enabled = on) }
        launchIo({ settings.setEnabled(on) }) { control.setRunning(on) }
    }

    fun regenerateToken() {
        launchIo(settings::regenerateToken) { result -> result.onSuccess { token -> setState { it.copy(token = token) } } }
    }

    private fun onServiceState(service: McpAgentState) {
        // The service persists the switch as off on these itself; mirror it so the switch never claims to be on.
        val stopped = service is McpAgentState.Failed || service is McpAgentState.StoppedIdle ||
            service is McpAgentState.StoppedByUser
        setState { it.copy(service = service, enabled = it.enabled && !stopped) }
        if (service is McpAgentState.Running) {
            launchIo({ addresses() }) { result -> result.onSuccess { list -> setState { it.copy(addresses = list) } } }
        }
    }
}
