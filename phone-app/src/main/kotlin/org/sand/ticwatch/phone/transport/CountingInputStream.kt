package org.sand.ticwatch.phone.transport

import java.io.InputStream

/**
 * Reports cumulative bytes read from [delegate] to [onRead]. Wrapping the read side
 * (rather than the write side each transport copies to) lets one decorator instrument
 * both [BluetoothClassicTransport] and [DataLayerTransport] without either knowing
 * progress reporting exists - they both drive their copy loop by reading a
 * [PayloadSource], so byte-level progress falls out of the read pace.
 */
class CountingInputStream(
    private val delegate: InputStream,
    private val onRead: (bytesReadSoFar: Long) -> Unit,
) : InputStream() {
    private var count = 0L

    override fun read(): Int {
        val b = delegate.read()
        if (b >= 0) {
            count++
            onRead(count)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = delegate.read(b, off, len)
        if (n > 0) {
            count += n
            onRead(count)
        }
        return n
    }

    override fun close() = delegate.close()
}
