package org.sand.ticwatch.watch.transport

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Produces a fresh [InputStream] over the same payload each time it's opened, so a
 * transport can be retried (e.g. by [FallbackDeviceLinkTransport]) without buffering
 * the whole payload in memory.
 */
fun interface PayloadSource {
    fun open(): InputStream
}

/**
 * Transport-agnostic way to push a payload to the paired phone. Implementations decide
 * how bytes actually reach the peer (Wear Data Layer, direct Bluetooth, ...); callers
 * depend only on this contract (DIP), so the transport can be swapped or composed
 * (see [FallbackDeviceLinkTransport]) without touching call sites.
 */
interface DeviceLinkTransport {
    suspend fun send(path: String, length: Long, source: PayloadSource): Result<Unit>
}

suspend fun DeviceLinkTransport.sendMessage(path: String, payload: ByteArray): Result<Unit> =
    send(path, payload.size.toLong()) { ByteArrayInputStream(payload) }

suspend fun DeviceLinkTransport.sendFile(path: String, file: File): Result<Unit> =
    send(path, file.length()) { file.inputStream() }
