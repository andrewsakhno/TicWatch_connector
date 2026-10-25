package org.sand.ticwatch.watch.transport

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
        return fallback.send(path, length, source)
    }
}
