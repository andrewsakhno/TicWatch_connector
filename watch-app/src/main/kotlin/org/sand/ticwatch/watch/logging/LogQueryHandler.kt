package org.sand.ticwatch.watch.logging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.sand.ticwatch.watch.datalayer.DebugQuery
import java.time.Duration
import java.time.Instant

/**
 * Answers `/debug/logs/request`: `{"request_id", "from_ms", "to_ms", "contains", "max_lines"}`, all
 * optional (default: the last [DEFAULT_LOOKBACK], [DEFAULT_MAX_LINES] lines). `request_id` is
 * echoed so the phone can match the reply to its caller.
 */
class LogQueryHandler(private val store: RotatingLogStore) : DebugQuery {

    override suspend fun answer(request: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        val json = request.takeIf { it.isNotEmpty() }?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
        val to = json.optLong(TO_MS).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: Instant.now()
        val from = json.optLong(FROM_MS).takeIf { it > 0 }?.let(Instant::ofEpochMilli) ?: to.minus(DEFAULT_LOOKBACK)
        val maxLines = json.optInt(MAX_LINES, DEFAULT_MAX_LINES).coerceIn(1, MAX_LINES_LIMIT)
        val contains = json.optString(CONTAINS).takeIf { it.isNotEmpty() }
        val result = store.query(LogQuery(from, to, contains, maxLines))
        JSONObject()
            .put(REQUEST_ID, json.optString(REQUEST_ID))
            .put(FROM_MS, from.toEpochMilli())
            .put(TO_MS, to.toEpochMilli())
            .put("truncated", result.truncated)
            .put("stored_bytes", store.totalBytes())
            .put("lines", JSONArray(result.lines))
    }

    private companion object {
        const val REQUEST_ID = "request_id"
        const val FROM_MS = "from_ms"
        const val TO_MS = "to_ms"
        const val CONTAINS = "contains"
        const val MAX_LINES = "max_lines"
        const val DEFAULT_MAX_LINES = 500
        const val MAX_LINES_LIMIT = 5_000
        val DEFAULT_LOOKBACK: Duration = Duration.ofHours(1)
    }
}
