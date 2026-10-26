package org.sand.ticwatch.watch.transport

import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 of a payload, as the lowercase hex string the resumable frame carries. */
object ContentHash {
    private const val ALGORITHM = "SHA-256"
    private const val BUFFER_SIZE = 64 * 1024

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
