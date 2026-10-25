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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.mcp.LocalAddressProvider
import org.sand.ticwatch.phone.mcp.MCP_PATH
import org.sand.ticwatch.phone.mcp.McpAgentService
import org.sand.ticwatch.phone.mcp.McpAgentState

/** Drawer screen: the "enable MCP agent" switch plus the URL and token an external model needs. */
@Composable
fun McpAgentScreen(appContainer: AppContainer) {
    val context = LocalContext.current
    val settings = appContainer.mcpAgentSettingsStore
    val coroutineScope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var port by remember { mutableIntStateOf(0) }
    val serviceState by McpAgentService.state.collectAsState()

    LaunchedEffect(Unit) {
        enabled = settings.isEnabled()
        token = settings.getToken()
        port = settings.getPort()
    }
    // The service persists the switch as off on these itself; mirror it so the switch never claims to be on.
    LaunchedEffect(serviceState) {
        if (serviceState is McpAgentState.Failed || serviceState is McpAgentState.StoppedIdle ||
            serviceState is McpAgentState.StoppedByUser
        ) {
            enabled = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(text = "Enable MCP agent", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { on ->
                enabled = on
                coroutineScope.launch {
                    settings.setEnabled(on)
                    if (on) McpAgentService.start(context) else McpAgentService.stop(context)
                }
            })
        }
        (serviceState as? McpAgentState.Failed)?.let {
            Text(text = "Couldn't start the server: ${it.reason}", color = MaterialTheme.colorScheme.error)
        }
        Text(
            text = "While enabled, any client on your network that knows the address and token can call " +
                "the agent's tools. Use only on a trusted network and keep the token private. " +
                "The agent turns itself off after 30 minutes without requests.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (enabled && serviceState is McpAgentState.Running) {
            Text(text = "Server address (same Wi-Fi / network)", style = MaterialTheme.typography.titleSmall)
            val urls = LocalAddressProvider.ipv4Addresses().map { "http://$it:$port$MCP_PATH" }
            if (urls.isEmpty()) Text(text = "No network address found")
            urls.forEach { CopyableLine(context, it) }
        }

        Text(text = "Access token (Authorization: Bearer)", style = MaterialTheme.typography.titleSmall)
        CopyableLine(context, token)
        OutlinedButton(onClick = { coroutineScope.launch { token = settings.regenerateToken() } }) {
            Text(text = "Generate new token")
        }

        Text(text = "Tools", style = MaterialTheme.typography.titleSmall)
        if (appContainer.mcpTools.isEmpty()) {
            Text(text = "No tools registered yet.")
        } else {
            appContainer.mcpTools.forEach { Text(text = "${it.name} - ${it.description}") }
        }

        Text(
            text = "Give the external model the address as an MCP server URL (Streamable HTTP) and the token " +
                "as a Bearer header. From a PC over USB: adb forward tcp:$port tcp:$port, " +
                "then use http://localhost:$port$MCP_PATH.",
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
        }) { Text(text = "Copy") }
    }
}
