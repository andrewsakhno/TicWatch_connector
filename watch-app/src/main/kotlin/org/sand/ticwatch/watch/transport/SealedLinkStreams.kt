package org.sand.ticwatch.watch.transport

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Which way a sealed stream flows; authenticated so a record can't be reflected back at its sender. */
enum class LinkDirection(val code: Byte) {
    PHONE_TO_WATCH(1),
    WATCH_TO_PHONE(2),
}

/**
 * Record layer of the Wi-Fi link (see [TransportProtocol]): AES-GCM with the key handed over the
 * bonded RFCOMM link. A stream starts with a random nonce prefix, so reconnecting under the same
 * key never reuses a nonce; each record is `size` (int) + ciphertext and goes out on flush, which
 * keeps the protocol's chunk/ACK ping-pong interactive. Mirrored by phone-app.
 */
private object LinkCipher {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val KEY_ALGORITHM = "AES"
    const val TAG_BITS = 128
    const val TAG_BYTES = TAG_BITS / Byte.SIZE_BITS
    const val NONCE_PREFIX_BYTES = 8
    const val NONCE_BYTES = NONCE_PREFIX_BYTES + Int.SIZE_BYTES

    /** One resumable chunk plus its size field fits a single record. */
    const val MAX_RECORD_PLAIN_BYTES = TransportProtocol.RESUMABLE_CHUNK_SIZE + Int.SIZE_BYTES

    fun create(mode: Int, key: ByteArray, prefix: ByteArray, counter: Int, direction: LinkDirection): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_BYTES).put(prefix).putInt(counter).array()
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(byteArrayOf(direction.code))
        }
    }
}

class SealedOutputStream(
    sink: OutputStream,
    private val key: ByteArray,
    private val direction: LinkDirection,
) : OutputStream() {
    private val output = DataOutputStream(
        BufferedOutputStream(sink, LinkCipher.MAX_RECORD_PLAIN_BYTES + LinkCipher.TAG_BYTES + Int.SIZE_BYTES),
    )
    private val prefix = ByteArray(LinkCipher.NONCE_PREFIX_BYTES).also(SecureRandom()::nextBytes)
    private val plain = ByteArray(LinkCipher.MAX_RECORD_PLAIN_BYTES)
    private var filled = 0
    private var counter = 0
    private var prefixSent = false

    override fun write(byte: Int) {
        write(byteArrayOf(byte.toByte()), 0, 1)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val size = minOf(plain.size - filled, end - position)
            bytes.copyInto(plain, filled, position, position + size)
            filled += size
            position += size
            if (filled == plain.size) sealRecord()
        }
    }

    override fun flush() {
        if (filled > 0) sealRecord()
        output.flush()
    }

    override fun close() {
        flush()
        output.close()
    }

    private fun sealRecord() {
        if (!prefixSent) {
            output.write(prefix)
            prefixSent = true
        }
        val sealed = try {
            LinkCipher.create(Cipher.ENCRYPT_MODE, key, prefix, counter++, direction).doFinal(plain, 0, filled)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to seal a link record", e)
        }
        output.writeInt(sealed.size)
        output.write(sealed)
        filled = 0
    }
}

class SealedInputStream(
    source: InputStream,
    private val key: ByteArray,
    private val direction: LinkDirection,
) : InputStream() {
    private val input = DataInputStream(source)
    private var prefix: ByteArray? = null
    private var counter = 0
    private var plain = ByteArray(0)
    private var position = 0

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and BYTE_MASK
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position == plain.size && !openNextRecord()) return -1
        val size = minOf(length, plain.size - position)
        plain.copyInto(bytes, offset, position, position + size)
        position += size
        return size
    }

    /** Reads and authenticates one record; false on a clean end of stream between records. */
    private fun openNextRecord(): Boolean {
        val noncePrefix = prefix ?: ByteArray(LinkCipher.NONCE_PREFIX_BYTES).also {
            try {
                input.readFully(it)
            } catch (_: EOFException) {
                return false
            }
            prefix = it
        }
        val size = try {
            input.readInt()
        } catch (_: EOFException) {
            return false
        }
        if (size !in (LinkCipher.TAG_BYTES + 1)..(LinkCipher.MAX_RECORD_PLAIN_BYTES + LinkCipher.TAG_BYTES)) {
            throw IOException("Link record of $size bytes is out of range")
        }
        val sealed = ByteArray(size).also(input::readFully)
        plain = try {
            LinkCipher.create(Cipher.DECRYPT_MODE, key, noncePrefix, counter++, direction).doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            throw IOException("Link record failed authentication", e)
        }
        position = 0
        return true
    }

    override fun close() {
        input.close()
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
