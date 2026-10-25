package org.sand.ticwatch.phone.mcp

import org.json.JSONArray
import org.json.JSONObject

private const val PROTOCOL_VERSION = "2025-03-26"
private const val SERVER_NAME = "TicWatch Connector"
private const val SERVER_VERSION = "1.0"
private const val JSON_RPC_VERSION = "2.0"
private const val ERR_PARSE = -32700
private const val ERR_METHOD_NOT_FOUND = -32601
private const val ERR_INVALID_PARAMS = -32602

/** JSON-RPC method names this server understands. */
private enum class McpMethod(val wireName: String) {
    INITIALIZE("initialize"),
    PING("ping"),
    TOOLS_LIST("tools/list"),
    TOOLS_CALL("tools/call"),
    ;

    companion object {
        fun fromWireName(name: String): McpMethod? = entries.firstOrNull { it.wireName == name }
    }
}

/** Transport-agnostic MCP (JSON-RPC 2.0) dispatcher: initialize, ping, tools/list, tools/call. */
class McpJsonRpcHandler(private val tools: List<McpTool>) {

    private val toolsByName = tools.associateBy { it.name }

    /** Returns the JSON response body, or null for notifications (which get no response). */
    suspend fun handle(body: String): String? {
        val request = try {
            JSONObject(body)
        } catch (e: Exception) {
            return error(null, ERR_PARSE, "Parse error").toString()
        }
        val id = request.opt("id") ?: return null // notification, e.g. notifications/initialized
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()
        return when (McpMethod.fromWireName(method)) {
            McpMethod.INITIALIZE -> result(id, initializeResult())
            McpMethod.PING -> result(id, JSONObject())
            McpMethod.TOOLS_LIST -> result(id, JSONObject().put("tools", toolList()))
            McpMethod.TOOLS_CALL -> callTool(id, params)
            null -> error(id, ERR_METHOD_NOT_FOUND, "Method not found: $method")
        }.toString()
    }

    private fun initializeResult() = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))

    private fun toolList() = JSONArray().also { array ->
        tools.forEach {
            array.put(
                JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.inputSchema),
            )
        }
    }

    private suspend fun callTool(id: Any, params: JSONObject): JSONObject {
        val tool = toolsByName[params.optString("name")]
            ?: return error(id, ERR_INVALID_PARAMS, "Unknown tool: ${params.optString("name")}")
        val (text, isError) = try {
            tool.call(params.optJSONObject("arguments") ?: JSONObject()) to false
        } catch (e: Exception) {
            (e.message ?: e.javaClass.simpleName) to true
        }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return result(id, JSONObject().put("content", content).put("isError", isError))
    }

    private fun result(id: Any, result: JSONObject) =
        JSONObject().put("jsonrpc", JSON_RPC_VERSION).put("id", id).put("result", result)

    private fun error(id: Any?, code: Int, message: String) = JSONObject()
        .put("jsonrpc", JSON_RPC_VERSION)
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
}
