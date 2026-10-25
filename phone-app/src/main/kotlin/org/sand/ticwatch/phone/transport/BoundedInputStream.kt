package org.sand.ticwatch.phone.transport

import java.io.InputStream

/** Reads at most [limit] bytes from [delegate], then reports EOF - without touching
 *  the delegate any further. Needed because a single Bluetooth socket connection now
 *  stays open past the payload (the sender waits for an ack byte before closing), so
 *  the receiving side can no longer treat "end of stream" as "end of payload". */
class BoundedInputStream(private val delegate: InputStream, private var remaining: Long) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val byte = delegate.read()
        if (byte != -1) remaining--
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val toRead = minOf(len.toLong(), remaining).toInt()
        val read = delegate.read(b, off, toRead)
        if (read > 0) remaining -= read
        return read
    }
}
