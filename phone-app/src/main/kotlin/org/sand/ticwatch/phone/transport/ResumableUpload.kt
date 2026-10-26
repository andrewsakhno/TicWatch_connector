package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothSocket
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

/** The receiver assembled the whole payload but its hash didn't match; it restarts from 0. */
class ContentHashMismatchException(path: String) : IOException("Content hash mismatch for $path")

/**
 * Sender half of the resumable RFCOMM frame (see [TransportProtocol]): the payload goes in
 * acknowledged chunks and a reconnect resumes from the offset the watch already holds for
 * the same content hash. One instance spans every retry of a single send, so
 * [confirmedOffset] tells the caller whether an attempt moved the transfer forward.
 */
internal class ResumableUpload(
    private val path: String,
    private val length: Long,
    private val source: PayloadSource,
    private val contentHash: String,
) {
    enum class Outcome { COMPLETED, PEER_IS_LEGACY }

    /** Bytes the receiver has confirmed so far; [NO_HANDSHAKE] until the first handshake. */
    var confirmedOffset: Long = NO_HANDSHAKE
        private set

    fun runOn(socket: BluetoothSocket): Outcome {
        val output = DataOutputStream(BufferedOutputStream(socket.outputStream, TransportProtocol.RESUMABLE_CHUNK_SIZE))
        val input = DataInputStream(socket.inputStream)

        output.writeUTF(TransportProtocol.RESUMABLE_FRAME_PATH)
        output.writeLong(TransportProtocol.RESUMABLE_FRAME_LEGACY_LENGTH)
        output.writeUTF(path)
        output.writeLong(length)
        output.writeUTF(contentHash)
        output.flush()

        when (val reply = input.read()) {
            TransportProtocol.RESUME_READY -> Unit
            TransportProtocol.ACK -> return Outcome.PEER_IS_LEGACY
            else -> throw IOException("Unexpected resumable handshake reply $reply for $path")
        }
        var offset = input.readLong()
        if (offset !in 0..length) throw IOException("Receiver offered resume offset $offset of $length for $path")
        confirmedOffset = offset

        source.open().use { payload ->
            payload.skipFully(offset)
            val buffer = ByteArray(TransportProtocol.RESUMABLE_CHUNK_SIZE)
            while (offset < length) {
                val size = payload.readUpTo(buffer, minOf(buffer.size.toLong(), length - offset).toInt())
                if (size <= 0) throw IOException("Payload of $path ended at $offset of $length bytes")
                output.writeInt(size)
                output.write(buffer, 0, size)
                output.flush()
                val reply = input.read()
                if (reply != TransportProtocol.ACK) throw IOException("Chunk at $offset of $path not acknowledged: $reply")
                offset += size
                confirmedOffset = offset
            }
        }

        output.writeInt(TransportProtocol.END_OF_CHUNKS)
        output.flush()
        return when (val reply = input.read()) {
            TransportProtocol.ACK -> Outcome.COMPLETED
            TransportProtocol.NACK -> throw ContentHashMismatchException(path)
            else -> throw IOException("Unexpected completion reply $reply for $path")
        }
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (read() < 0) throw IOException("Payload of $path shorter than resume offset $count")
                remaining--
            }
        }
    }

    /** Fills [buffer] up to [limit] bytes unless the stream ends first (readNBytes needs API 33). */
    private fun InputStream.readUpTo(buffer: ByteArray, limit: Int): Int {
        var filled = 0
        while (filled < limit) {
            val read = read(buffer, filled, limit - filled)
            if (read < 0) break
            filled += read
        }
        return filled
    }

    companion object {
        const val NO_HANDSHAKE = -1L
    }
}
