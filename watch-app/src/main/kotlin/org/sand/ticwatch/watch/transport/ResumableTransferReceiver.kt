package org.sand.ticwatch.watch.transport

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Receiver half of the resumable RFCOMM frame (see [TransportProtocol]). Chunks are
 * appended to `<sha256>.part` in [directory] and acknowledged once written, so a
 * reconnect after a dropped link resumes from that file's length. Partial files of any
 * other hash are deleted at handshake, and the finished file is only handed on when its
 * hash matches - a resume can never mix bytes of two different payloads.
 *
 * One transfer at a time: a reconnect for a new attempt closes the previous attempt's
 * socket (which may still be blocked on a dead link) and waits for it to let go of the
 * partial file before appending to it.
 */
class ResumableTransferReceiver(private val directory: File) {

    private val lock = Mutex()

    @Volatile
    private var activeConnection: Closeable? = null

    /**
     * Runs the rest of a resumable frame whose legacy header was already read from [input].
     * [onComplete] gets the real path and the verified file, which is deleted afterwards.
     */
    suspend fun receive(
        connection: Closeable,
        input: DataInputStream,
        output: DataOutputStream,
        onComplete: suspend (path: String, file: File) -> Unit,
    ) {
        val path = input.readUTF()
        val length = input.readLong()
        val contentHash = input.readUTF()
        if (length < 0 || !contentHash.matches(HASH_PATTERN)) throw IOException("Malformed resumable header for $path")

        activeConnection?.let { runCatching { it.close() } }
        lock.withLock {
            activeConnection = connection
            try {
                val part = preparePartFile(contentHash, length)
                var offset = part.length()
                Log.i(TAG, "Resumable $path: $length bytes, resuming at $offset")
                output.writeByte(TransportProtocol.RESUME_READY)
                output.writeLong(offset)
                output.flush()

                FileOutputStream(part, true).use { file ->
                    val buffer = ByteArray(TransportProtocol.RESUMABLE_CHUNK_SIZE)
                    while (true) {
                        val size = input.readInt()
                        if (size == TransportProtocol.END_OF_CHUNKS) break
                        if (size !in 1..buffer.size || offset + size > length) {
                            throw IOException("Chunk of $size bytes at $offset doesn't fit $path ($length bytes)")
                        }
                        input.readFully(buffer, 0, size)
                        file.write(buffer, 0, size)
                        offset += size
                        output.writeByte(TransportProtocol.ACK)
                        output.flush()
                    }
                }

                val verified = offset == length && part.inputStream().use(ContentHash::sha256Hex) == contentHash
                if (verified) {
                    try {
                        onComplete(path, part)
                    } finally {
                        part.delete()
                    }
                } else {
                    Log.w(TAG, "Resumable $path failed verification at $offset of $length bytes, dropping it")
                    part.delete()
                }
                output.writeByte(if (verified) TransportProtocol.ACK else TransportProtocol.NACK)
                output.flush()
            } finally {
                if (activeConnection === connection) activeConnection = null
            }
        }
    }

    /** Keeps only this hash's partial file, discarding it too if it's longer than the payload. */
    private fun preparePartFile(contentHash: String, length: Long): File {
        directory.mkdirs()
        val part = File(directory, contentHash + PART_SUFFIX)
        directory.listFiles()?.filter { it != part }?.forEach { it.delete() }
        if (part.length() > length) part.delete()
        return part
    }

    companion object {
        private const val TAG = "ResumableReceiver"
        private const val PART_SUFFIX = ".part"
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
