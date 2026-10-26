package org.sand.ticwatch.phone.transport

import android.util.Log

/**
 * Tries [primary] first and only falls through to [fallback] on failure (OCP: adding
 * this policy doesn't require changing either transport implementation).
 */
class FallbackDeviceLinkTransport(
    private val primary: DeviceLinkTransport,
    private val fallback: DeviceLinkTransport,
) : DeviceLinkTransport {

    override suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit> {
        val primaryResult = primary.send(path, length, source)
        if (primaryResult.isSuccess) return primaryResult
        val primaryError = primaryResult.exceptionOrNull()
        Log.w(TAG, "Primary transport failed for $path, falling back", primaryError)
        // Keep the primary cause on the final failure: callers only log what they get back, and
        // the fallback's error alone hides why the preferred link failed in the first place.
        return fallback.send(path, length, source).onFailure { fallbackError ->
            if (primaryError != null && primaryError !== fallbackError) fallbackError.addSuppressed(primaryError)
        }
    }

    companion object {
        private const val TAG = "FallbackTransport"
    }
}
