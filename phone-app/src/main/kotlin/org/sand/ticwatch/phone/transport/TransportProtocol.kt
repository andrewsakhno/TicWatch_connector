package org.sand.ticwatch.phone.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import java.util.UUID

/**
 * Wire-level contract for [BluetoothClassicTransport]/`BluetoothLinkServerService` on
 * both phone-app and watch-app. Must be kept in sync manually between the two
 * independent Gradle projects, same as `datalayer/DataLayerPaths.kt`.
 *
 * Each send opens a fresh RFCOMM socket, writes `path` (UTF), then `length` (unused by
 * the reader - the payload simply runs to end-of-stream when the sender closes the
 * socket), then the payload bytes, then waits for the receiver's one-byte [ACK]/[NACK]
 * reply before closing. One socket carries exactly one payload.
 *
 * Large phone->watch payloads (APKs, > [RESUMABLE_THRESHOLD]) use the resumable frame
 * instead, so a dropped link costs one chunk rather than the whole transfer:
 * 1. the legacy header with path [RESUMABLE_FRAME_PATH] and length 0, then the real
 *    `path` (UTF), total `length` (long) and the payload's SHA-256 hex (UTF);
 * 2. the receiver replies [RESUME_READY] + the offset (long) it already holds for that
 *    hash (partial data of any other hash is discarded, so a resume can never splice
 *    two different files);
 * 3. the sender writes chunks as `size` (int, 1..[RESUMABLE_CHUNK_SIZE]) + bytes, each
 *    answered by [ACK] once it is on the receiver's disk;
 * 4. a `size` of [END_OF_CHUNKS] ends the payload; the receiver verifies the hash and
 *    replies [ACK] (handled) or [NACK] (mismatch - partial data dropped, restart from 0).
 * A receiver that predates this frame treats the marker as an empty unknown message and
 * replies a plain [ACK] instead of [RESUME_READY]; the sender then falls back to the
 * one-shot frame.
 */
object TransportProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("7b1e6b9e-6e0a-4c9b-9e2e-6a2a2f7a9b10")
    const val SERVICE_NAME = "TicWatchConnectorLink"

    /** Receiver's one-byte reply: the payload was fully handled; the sender may drop it. */
    const val ACK: Int = 1

    /**
     * Receiver's one-byte reply: the payload arrived but wasn't durably handled (e.g. the
     * Health Connect write failed); the sender must keep it and retry later.
     */
    const val NACK: Int = 0

    /** Legacy-header path announcing a resumable frame (an old receiver ignores it). */
    const val RESUMABLE_FRAME_PATH = "/transport/resumable/v1"

    /** Legacy-header length sent with [RESUMABLE_FRAME_PATH]: an old receiver reads nothing more. */
    const val RESUMABLE_FRAME_LEGACY_LENGTH = 0L

    /** Receiver's handshake reply to a resumable frame, followed by the resume offset. */
    const val RESUME_READY: Int = 2

    /** Payloads larger than this go over the resumable frame. */
    const val RESUMABLE_THRESHOLD = 256 * 1024L

    /** Largest chunk the resumable frame carries; one ACK round trip per chunk. */
    const val RESUMABLE_CHUNK_SIZE = 64 * 1024

    /** Chunk size that terminates a resumable payload. */
    const val END_OF_CHUNKS = 0

    /**
     * Major device classes a bonded watch may advertise, in preference order. The
     * TicWatch reports Uncategorized (CoD 0x001F00) after a factory reset rather than
     * Wearable, so matching Wearable alone misses it.
     */
    private val WATCH_MAJOR_CLASSES = listOf(
        BluetoothClass.Device.Major.WEARABLE,
        BluetoothClass.Device.Major.UNCATEGORIZED,
    )

    /** Finds the bonded device that looks like the watch, by [WATCH_MAJOR_CLASSES] preference. */
    fun findBondedWatch(adapter: BluetoothAdapter): BluetoothDevice? =
        runCatching {
            val bonded = adapter.bondedDevices.orEmpty()
            WATCH_MAJOR_CLASSES.firstNotNullOfOrNull { majorClass ->
                bonded.firstOrNull { it.bluetoothClass?.majorDeviceClass == majorClass }
            }
        }.getOrNull()
}
