package com.roverspi.memsgauge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.roverspi.memsgauge.protocol.ByteTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

/**
 * [ByteTransport] implementation over a BLE GATT connection to a UART bridge
 * module. [connectToDevice] tries each profile in
 * [BleUartProfiles.KNOWN_PROFILES] in turn until one matches the services the
 * module actually reports, and enables notifications on its RX characteristic.
 *
 * Not yet verified against real hardware -- exercise this against a purchased
 * HM-10/HC-08/NUS-clone module wired to the ECU before relying on it.
 */
class BleUartTransport(private val context: Context) : ByteTransport {

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val incomingBytes = Channel<Byte>(capacity = Channel.UNLIMITED)

    /**
     * Fired when the link drops AFTER [connectToDevice] already completed --
     * a disconnect during the initial handshake is reported via that
     * function's own return value instead. Lets [BleEcuDataSource] notice a
     * real link loss immediately rather than waiting for the poll loop's
     * staleness timeout to catch it.
     */
    var onUnexpectedDisconnect: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    suspend fun connectToDevice(device: BluetoothDevice): BleUartProfile? =
        suspendCoroutine { continuation ->
            var resumed = false
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(null)
                        } else {
                            // A disconnect after the handshake already
                            // finished -- previously ignored entirely, which
                            // left connectionState stuck on CONNECTED while
                            // the link was actually dead.
                            Log.w(TAG, "BLE disconnected unexpectedly after handshake (status=$status)")
                            gatt = null
                            txCharacteristic = null
                            onUnexpectedDisconnect?.invoke()
                        }
                    }
                }

                @Suppress("DEPRECATION")
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val profile = BleUartProfiles.KNOWN_PROFILES.firstOrNull { candidate ->
                        g.getService(candidate.serviceUuid)?.getCharacteristic(candidate.txCharUuid) != null
                    }
                    Log.d(TAG, "onServicesDiscovered: status=$status matchedProfile=$profile")
                    if (profile != null) {
                        val service = g.getService(profile.serviceUuid)
                        txCharacteristic = service?.getCharacteristic(profile.txCharUuid)
                        val rxChar = service?.getCharacteristic(profile.rxCharUuid)
                        if (rxChar != null) {
                            g.setCharacteristicNotification(rxChar, true)
                            rxChar.getDescriptor(CCCD_UUID)?.let { descriptor ->
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(descriptor)
                            }
                        }
                    }
                    if (!resumed) {
                        resumed = true
                        continuation.resume(profile)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    characteristic.value?.forEach { byte -> incomingBytes.trySendBlocking(byte) }
                }
            }
            gatt = device.connectGatt(context, false, callback)
        }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    override suspend fun write(bytes: ByteArray): Boolean {
        val characteristic = txCharacteristic ?: run {
            Log.w(TAG, "write: no tx characteristic (not connected?)")
            return false
        }
        val g = gatt ?: run {
            Log.w(TAG, "write: no gatt connection")
            return false
        }
        characteristic.value = bytes
        val ok = g.writeCharacteristic(characteristic)
        if (!ok) Log.w(TAG, "write: writeCharacteristic() returned false")
        return ok
    }

    override suspend fun readExactly(count: Int, timeoutMs: Long): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            ByteArray(count) { incomingBytes.receive() }
        }

    override fun flushStaleBytes() {
        while (incomingBytes.tryReceive().isSuccess) {
            // discard -- draining whatever is already queued
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        txCharacteristic = null
    }

    private companion object {
        const val TAG = "RoverMEMS"
    }
}
