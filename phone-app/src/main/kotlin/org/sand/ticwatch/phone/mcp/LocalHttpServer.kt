package org.sand.ticwatch.phone.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private const val MAX_BODY_BYTES = 8_000_000
private const val SOCKET_TIMEOUT_MS = 30_000
private const val HTTP_OK = 200
private const val HTTP_ACCEPTED = 202
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_NOT_FOUND = 404
private const val HTTP_METHOD_NOT_ALLOWED = 405
private const val HTTP_TOO_LARGE = 413
private const val HTTP_POST = "POST"
private const val LINE_FEED = 10

/** Path the MCP endpoint is served on, i.e. `http://<host>:<port>/mcp`. */
const val MCP_PATH = "/mcp"

/**
 * Minimal HTTP/1.1 server speaking MCP "Streamable HTTP" (one JSON response per POST).
 * Every request must carry `Authorization: Bearer <token>`.
 */
class LocalHttpServer(
    private val port: Int,
    private val token: String,
    private val handler: McpJsonRpcHandler,
    private val onActivity: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    /** Connections currently being served; the idle timer must not run while this is above zero. */
    val activeConnections = AtomicInteger(0)

    fun start() {
        val socket = ServerSocket(port)
        serverSocket = socket
        scope.launch {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                launch {
                    activeConnections.incrementAndGet()
                    try {
                        val authenticated = client.use { serve(it) }
                        if (authenticated) onActivity()
                    } finally {
                        activeConnections.decrementAndGet()
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        scope.cancel()
    }

    /** Serves one connection; returns true if the request carried a valid token. */
    private suspend fun serve(socket: Socket): Boolean {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return false
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            respond(out, HTTP_BAD_REQUEST, "")
            return false
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) {
            respond(out, HTTP_TOO_LARGE, "")
            return false
        }
        val body = readBody(input, length)
        val authenticated = headers["authorization"] == "Bearer $token"
        if (authenticated) onActivity()

        when {
            !authenticated -> respond(out, HTTP_UNAUTHORIZED, "")
            parts[1].substringBefore('?') != MCP_PATH -> respond(out, HTTP_NOT_FOUND, "")
            parts[0] != HTTP_POST -> respond(out, HTTP_METHOD_NOT_ALLOWED, "")
            else -> {
                val response = handler.handle(body)
                if (response == null) respond(out, HTTP_ACCEPTED, "") else respond(out, HTTP_OK, response)
            }
        }
        return authenticated
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return buf.toString(Charsets.UTF_8)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == LINE_FEED) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun respond(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
