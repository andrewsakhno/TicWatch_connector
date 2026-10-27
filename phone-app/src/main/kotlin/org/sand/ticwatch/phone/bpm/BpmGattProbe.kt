package org.sand.ticwatch.phone.bpm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.UUID

/**
 * Debug-only GATT explorer for the bonded blood-pressure cuff: waits (autoConnect) until the cuff
 * advertises - it typically only does so after a measurement - then logs every service and
 * characteristic, reads the readable ones and subscribes to every notify/indicate one, logging all
 * values as hex under [TAG] until [PROBE_DURATION_MS] elapses. Then writes each of [commands] to
 * [commandCharacteristic], [COMMAND_GAP_MS] apart, so the replies show up as notifications. Re-runs on every
 * reconnect (the cuff sleeps ~2 min after use). Used to reverse the vendor protocol.
 */
@SuppressLint("MissingPermission")
class BpmGattProbe(
    private val context: Context,
    private val commands: List<ByteArray> = emptyList(),
    private val commandCharacteristic: UUID = BiolandProtocol.COMMAND_CHARACTERISTIC,
    private val deviceNamePrefix: String = BiolandProtocol.DEVICE_NAME_PREFIX,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<(BluetoothGatt) -> Unit>()
    private var gatt: BluetoothGatt? = null

    fun start() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.let(::findBondedCuff)
        if (device == null) {
            Log.w(TAG, "No bonded device named $deviceNamePrefix*")
            return
        }
        Log.i(TAG, "Waiting for ${device.name} (${device.address}, type=${device.type}) to advertise")
        gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        handler.postDelayed(::stop, PROBE_DURATION_MS)
    }

    fun stop() {
        gatt?.close()
        gatt = null
        Log.i(TAG, "Probe stopped")
    }

    private fun findBondedCuff(adapter: BluetoothAdapter): BluetoothDevice? =
        adapter.bondedDevices.firstOrNull { it.name?.startsWith(deviceNamePrefix, ignoreCase = true) == true }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "Connection state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> gatt.connect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Services discovered status=$status")
            pending.clear()
            gatt.services.forEach { service ->
                Log.i(TAG, "Service ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    Log.i(TAG, "  Char ${characteristic.uuid} props=0x${characteristic.properties.toString(HEX_RADIX)}")
                    queueFor(characteristic)
                }
            }
            queueCommands(gatt)
            runNext(gatt)
        }

        @Deprecated("Pre-API 33 callback, still the one invoked on older devices")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "Wrote ${characteristic.uuid} status=$status")
            handler.postDelayed({ runNext(gatt) }, COMMAND_GAP_MS)
        }

        @Deprecated("Pre-API 33 callback, still the one invoked on older devices")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "Read ${characteristic.uuid} status=$status value=${characteristic.value.toHex()}")
            runNext(gatt)
        }

        @Deprecated("Pre-API 33 callback, still the one invoked on older devices")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            Log.i(TAG, "Notify ${characteristic.uuid} value=${characteristic.value.toHex()}")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "Subscribed ${descriptor.characteristic.uuid} status=$status")
            runNext(gatt)
        }
    }

    private fun queueFor(characteristic: BluetoothGattCharacteristic) {
        val properties = characteristic.properties
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            pending.add { it.readCharacteristic(characteristic) }
        }
        val subscription = when {
            properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }
        val cccd = characteristic.getDescriptor(BiolandProtocol.CLIENT_CHARACTERISTIC_CONFIG) ?: return
        pending.add { gatt ->
            gatt.setCharacteristicNotification(characteristic, true)
            @Suppress("DEPRECATION")
            cccd.value = subscription
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun queueCommands(gatt: BluetoothGatt) {
        val target = gatt.services.firstNotNullOfOrNull { it.getCharacteristic(commandCharacteristic) }
        if (target == null) {
            if (commands.isNotEmpty()) Log.w(TAG, "No characteristic $commandCharacteristic to write commands to")
            return
        }
        commands.forEach { command ->
            pending.add { gatt ->
                Log.i(TAG, "Writing ${command.toHex()} to ${target.uuid}")
                @Suppress("DEPRECATION")
                target.value = command
                @Suppress("DEPRECATION")
                if (!gatt.writeCharacteristic(target)) {
                    Log.w(TAG, "Write not started")
                    runNext(gatt)
                }
            }
        }
    }

    private fun runNext(gatt: BluetoothGatt) {
        pending.pollFirst()?.invoke(gatt) ?: Log.i(TAG, "All reads/subscriptions issued")
    }

    private fun ByteArray?.toHex(): String = this?.joinToString(" ") { "%02X".format(it) } ?: "null"

    companion object {
        private const val PROBE_DURATION_MS = 10 * 60 * 1000L
        private const val HEX_RADIX = 16
        private const val COMMAND_GAP_MS = 1500L
        private const val TAG = "BpmGattProbe"
    }
}

/**
 * Parses the probe's adb command spec: `;`-separated hex frames without checksum, where `{NOW}` expands to the
 * local `yy MM dd HH mm ss`; each frame gets the Bioland checksum appended.
 */
object BpmProbeCommands {
    private const val FRAME_SEPARATOR = ";"
    private const val NOW_TOKEN = "{NOW}"
    private const val HEX_RADIX = 16
    private const val HEX_DIGITS_PER_BYTE = 2

    fun parse(spec: String, now: LocalDateTime): List<ByteArray> =
        spec.split(FRAME_SEPARATOR).map(String::trim).filter(String::isNotEmpty).map { frame ->
            val hex = frame.replace(NOW_TOKEN, clockHex(now)).replace(" ", "")
            val body = hex.chunked(HEX_DIGITS_PER_BYTE).map { it.toInt(HEX_RADIX) }
            (body + BiolandProtocol.checksum(body)).map(Int::toByte).toByteArray()
        }

    private fun clockHex(now: LocalDateTime): String =
        listOf(now.year - BiolandProtocol.YEAR_BASE, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second)
            .joinToString("") { "%02X".format(it) }
}
