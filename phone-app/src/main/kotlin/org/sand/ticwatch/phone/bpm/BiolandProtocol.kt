package org.sand.ticwatch.phone.bpm

import java.time.LocalDateTime
import java.util.UUID

/**
 * Bioland cuff vendor protocol (reversed from live sessions, not vendor-documented): service 0x1000, commands are
 * written to 0x1001, everything comes back as notifications on 0x1002. Frames in both directions are
 * `<header> <length> <type> <payload> <checksum>`, checksum = low byte of (sum of all preceding bytes + 2); the cuff
 * sends header 0x55, the phone 0x5A. Commands carry the phone's `yy MM dd HH mm ss`, which also sets the cuff clock.
 */
object BiolandProtocol {
    val SERVICE: UUID = UUID.fromString("00001000-0000-1000-8000-00805f9b34fb")
    val COMMAND_CHARACTERISTIC: UUID = UUID.fromString("00001001-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("00001002-0000-1000-8000-00805f9b34fb")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val DEVICE_NAME_PREFIX = "Bioland"

    internal const val CHECKSUM_BIAS = 2
    internal const val BYTE_MASK = 0xFF
    internal const val YEAR_BASE = 2000

    internal fun checksum(bytes: List<Int>): Int = (bytes.sum() + CHECKSUM_BIAS) and BYTE_MASK
}

/**
 * Phone -> cuff requests. DEVICE_INFO answers a [BiolandFrame.DeviceInfo]; UPLOAD_RECORDS answers one
 * [BiolandFrame.Result] per stored measurement not uploaded yet (none once they are). Both set the cuff clock.
 */
enum class BiolandCommand(val code: Int) {
    DEVICE_INFO(0x00),
    UPLOAD_RECORDS(0x03),
}

object BiolandCommandEncoder {
    private const val HEADER = 0x5A
    private const val LENGTH = 10

    fun encode(command: BiolandCommand, now: LocalDateTime): ByteArray {
        val body = listOf(
            HEADER, LENGTH, command.code,
            now.year - BiolandProtocol.YEAR_BASE, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second,
        )
        return (body + BiolandProtocol.checksum(body)).map(Int::toByte).toByteArray()
    }
}

/** A finished cuff measurement as the cuff reported it; [deviceClock] is its start on the cuff clock. */
data class CuffReading(val systolic: Int, val diastolic: Int, val pulse: Int, val deviceClock: LocalDateTime?)

/** Frame types the cuff sends. */
enum class BiolandFrameType(val code: Int) {
    DEVICE_INFO(0x00),
    CUFF_PRESSURE(0x02),
    RESULT(0x03);

    companion object {
        fun of(code: Int): BiolandFrameType? = entries.firstOrNull { it.code == code }
    }
}

/** Decoded notification. [Other] covers well-formed frames nothing consumes (e.g. 0xEE, an error reply). */
sealed interface BiolandFrame {
    data class DeviceInfo(val batteryPercent: Int, val serial: String) : BiolandFrame
    data object CuffPressure : BiolandFrame
    data class Result(val reading: CuffReading) : BiolandFrame
    data class Other(val type: Int) : BiolandFrame
}

object BiolandFrameDecoder {
    private const val HEADER = 0x55
    private const val HEADER_INDEX = 0
    private const val LENGTH_INDEX = 1
    private const val TYPE_INDEX = 2

    // `55 0E 03 yy MM dd HH mm ff SYS 00 DIA PULSE cs`: ff was 0x0A on a stored record and 0x00 on live ones (meaning
    // unknown), so pressures are read as single bytes rather than 16-bit values.
    private const val RESULT_LENGTH = 14
    private const val RESULT_CLOCK_INDEX = 3
    private const val RESULT_SYSTOLIC_INDEX = 9
    private const val RESULT_DIASTOLIC_INDEX = 11
    private const val RESULT_PULSE_INDEX = 12

    // `55 12 00 25 01 BATTERY 01 <10 ASCII serial> cs`: the other bytes are constant so far.
    private const val INFO_MIN_LENGTH = 8
    private const val INFO_BATTERY_INDEX = 5
    private const val INFO_SERIAL_INDEX = 7

    /** Null when the bytes aren't a complete, checksum-valid frame. */
    fun decode(bytes: ByteArray): BiolandFrame? {
        if (bytes.size <= TYPE_INDEX || bytes.u8(HEADER_INDEX) != HEADER || bytes.u8(LENGTH_INDEX) != bytes.size) return null
        if (BiolandProtocol.checksum((0 until bytes.lastIndex).map { bytes.u8(it) }) != bytes.u8(bytes.lastIndex)) return null
        val type = bytes.u8(TYPE_INDEX)
        return when (BiolandFrameType.of(type)) {
            BiolandFrameType.RESULT -> if (bytes.size == RESULT_LENGTH) result(bytes) else null
            BiolandFrameType.DEVICE_INFO -> if (bytes.size >= INFO_MIN_LENGTH) deviceInfo(bytes) else null
            BiolandFrameType.CUFF_PRESSURE -> BiolandFrame.CuffPressure
            null -> BiolandFrame.Other(type)
        }
    }

    private fun result(bytes: ByteArray) = BiolandFrame.Result(
        CuffReading(
            systolic = bytes.u8(RESULT_SYSTOLIC_INDEX),
            diastolic = bytes.u8(RESULT_DIASTOLIC_INDEX),
            pulse = bytes.u8(RESULT_PULSE_INDEX),
            deviceClock = deviceClock(bytes, RESULT_CLOCK_INDEX),
        ),
    )

    private fun deviceInfo(bytes: ByteArray) = BiolandFrame.DeviceInfo(
        batteryPercent = bytes.u8(INFO_BATTERY_INDEX),
        serial = String(bytes, INFO_SERIAL_INDEX, bytes.lastIndex - INFO_SERIAL_INDEX, Charsets.US_ASCII),
    )

    private fun deviceClock(bytes: ByteArray, at: Int): LocalDateTime? = runCatching {
        LocalDateTime.of(
            BiolandProtocol.YEAR_BASE + bytes.u8(at), bytes.u8(at + 1), bytes.u8(at + 2), bytes.u8(at + 3), bytes.u8(at + 4),
        )
    }.getOrNull()

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and BiolandProtocol.BYTE_MASK
}
