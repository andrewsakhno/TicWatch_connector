package org.sand.ticwatch.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.mcp.LocalAddressProvider
import org.sand.ticwatch.phone.mcp.MCP_PATH
import org.sand.ticwatch.phone.mcp.McpAgentService
import org.sand.ticwatch.phone.mcp.McpAgentState
import org.sand.ticwatch.phone.ui.rememberStore

/** Drawer screen: the "enable MCP agent" switch plus the URL and token an external model needs. */
@Composable
fun McpAgentScreen(appContainer: AppContainer) {
    val context = LocalContext.current
    val store = rememberStore(appContainer) { scope, io ->
        McpAgentStore(
            appContainer.mcpAgentSettingsStore, McpAgentService.state,
            control = { on -> if (on) McpAgentService.start(context) else McpAgentService.stop(context) },
            addresses = LocalAddressProvider::ipv4Addresses,
            scope = scope, ioDispatcher = io,
        )
    }
    val state by store.state.collectAsState()
    val enabled = state.enabled
    val token = state.token
    val port = state.port
    val serviceState = state.service

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.mcp_enable), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = store::setEnabled)
        }
        (serviceState as? McpAgentState.Failed)?.let {
            Text(text = stringResource(R.string.mcp_start_failed, it.reason), color = MaterialTheme.colorScheme.error)
        }
        Text(
            text = stringResource(R.string.mcp_warning),
            style = MaterialTheme.typography.bodySmall,
        )

        if (enabled && serviceState is McpAgentState.Running) {
            Text(text = stringResource(R.string.mcp_address), style = MaterialTheme.typography.titleSmall)
            val urls = state.addresses.map { "http://$it:$port$MCP_PATH" }
            if (urls.isEmpty()) Text(text = stringResource(R.string.mcp_no_address))
            urls.forEach { CopyableLine(context, it) }
        }

        Text(text = stringResource(R.string.mcp_token), style = MaterialTheme.typography.titleSmall)
        CopyableLine(context, token)
        OutlinedButton(onClick = store::regenerateToken) {
            Text(text = stringResource(R.string.mcp_new_token))
        }

        Text(text = stringResource(R.string.mcp_tools), style = MaterialTheme.typography.titleSmall)
        if (appContainer.mcpTools.isEmpty()) {
            Text(text = stringResource(R.string.mcp_no_tools))
        } else {
            appContainer.mcpTools.forEach { Text(text = "${it.name} - ${it.description}") }
        }

        Text(
            text = stringResource(R.string.mcp_usage, port, MCP_PATH),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CopyableLine(context: Context, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("mcp", value))
        }) { Text(text = stringResource(R.string.copy)) }
    }
}
