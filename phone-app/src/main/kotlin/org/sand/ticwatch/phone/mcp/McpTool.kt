package org.sand.ticwatch.phone.mcp

import org.json.JSONObject

/** One callable MCP tool. New capabilities are added by registering another implementation in [org.sand.ticwatch.phone.AppContainer.mcpTools] (OCP). */
interface McpTool {
    val name: String
    val description: String

    /** JSON Schema object describing [call]'s arguments. */
    val inputSchema: JSONObject

    /** Returns the text payload handed back to the model; throw to report a tool error. */
    suspend fun call(arguments: JSONObject): String
}
