package org.sand.ticwatch.phone.bpm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayDeque

/** Receives what the cuff reports during a connection. */
interface CuffEventListener {
    /** [live]: measured during this connection; otherwise a stored record uploaded on request. */
    fun onReading(reading: CuffReading, live: Boolean)

    fun onDeviceInfo(info: BiolandFrame.DeviceInfo)
}

/**
 * Keeps a background (autoConnect) GATT connection to the bonded Bioland cuff: the cuff only advertises while it is
 * switched on, so the stack connects whenever it is. On each connection the client subscribes to the cuff's
 * notifications, then asks for the device info and the not-yet-uploaded stored records - both commands also set the
 * cuff clock to the phone's, so it recovers after a battery change. Unbonded cuffs are ignored - pair it once in the
 * system Bluetooth settings.
 */
@SuppressLint("MissingPermission")
class BiolandCuffClient(
    private val context: Context,
    private val listener: CuffEventListener,
    private val clock: Clock,
    private val zone: ZoneId,
) {
    private var gatt: BluetoothGatt? = null
    private val pendingCommands = ArrayDeque<BiolandCommand>()

    /** Whether this connection has streamed cuff pressure, i.e. a result arriving now is a live measurement. */
    @Volatile
    private var measuring = false

    fun start() {
        if (gatt != null) return
        val device = findBondedCuff()
        if (device == null) {
            Log.i(TAG, "No bonded ${BiolandProtocol.DEVICE_NAME_PREFIX}* cuff, not connecting")
            return
        }
        Log.i(TAG, "Waiting for cuff ${device.address}")
        gatt = runCatching { device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE) }
            .onFailure { Log.w(TAG, "connectGatt failed", it) }
            .getOrNull()
    }

    fun stop() {
        gatt?.close()
        gatt = null
    }

    private fun findBondedCuff(): BluetoothDevice? = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.firstOrNull { it.name?.startsWith(BiolandProtocol.DEVICE_NAME_PREFIX, ignoreCase = true) == true }
    }.onFailure { Log.w(TAG, "Can't list bonded devices", it) }.getOrNull()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "Cuff connection state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    measuring = false
                    gatt.discoverServices()
                }
                // Re-arms the background connection for the cuff's next power-on.
                BluetoothProfile.STATE_DISCONNECTED -> gatt.connect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(BiolandProtocol.SERVICE)?.getCharacteristic(BiolandProtocol.NOTIFY_CHARACTERISTIC)
            val cccd = characteristic?.getDescriptor(BiolandProtocol.CLIENT_CHARACTERISTIC_CONFIG)
            if (cccd == null) {
                Log.w(TAG, "Cuff has no result characteristic (status=$status)")
                return
            }
            gatt.setCharacteristicNotification(characteristic, true)
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingCommands.clear()
            pendingCommands.addAll(SESSION_COMMANDS)
            sendNextCommand(gatt)
        }

        @Deprecated("Pre-API 33 callback, still the one invoked on older devices")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Cuff command write failed status=$status")
            sendNextCommand(gatt)
        }

        @Deprecated("Pre-API 33 callback, still the one invoked on older devices")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            onFrame(characteristic.value ?: return)
        }
    }

    private fun sendNextCommand(gatt: BluetoothGatt) {
        val command = pendingCommands.pollFirst() ?: return
        val target = gatt.getService(BiolandProtocol.SERVICE)?.getCharacteristic(BiolandProtocol.COMMAND_CHARACTERISTIC) ?: return
        @Suppress("DEPRECATION")
        target.value = BiolandCommandEncoder.encode(command, LocalDateTime.now(clock.withZone(zone)))
        @Suppress("DEPRECATION")
        if (!gatt.writeCharacteristic(target)) Log.w(TAG, "Cuff command $command not sent")
    }

    private fun onFrame(bytes: ByteArray) {
        when (val frame = BiolandFrameDecoder.decode(bytes)) {
            is BiolandFrame.Result -> {
                Log.i(TAG, "Cuff result ${frame.reading} live=$measuring")
                listener.onReading(frame.reading, measuring)
            }
            is BiolandFrame.DeviceInfo -> {
                Log.i(TAG, "Cuff info $frame")
                listener.onDeviceInfo(frame)
            }
            BiolandFrame.CuffPressure -> measuring = true
            is BiolandFrame.Other -> Log.i(TAG, "Cuff frame type 0x${frame.type.toString(HEX_RADIX)}: ${bytes.toHex()}")
            null -> Log.w(TAG, "Malformed cuff frame ${bytes.toHex()}")
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private companion object {
        const val TAG = "BiolandCuffClient"
        const val HEX_RADIX = 16
        val SESSION_COMMANDS = listOf(BiolandCommand.DEVICE_INFO, BiolandCommand.UPLOAD_RECORDS)
    }
}
