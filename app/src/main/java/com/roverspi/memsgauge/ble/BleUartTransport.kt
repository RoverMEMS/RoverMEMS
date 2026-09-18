package com.roverspi.memsgauge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.roverspi.memsgauge.logging.DebugLog as Log
import com.roverspi.memsgauge.protocol.ByteTransport
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
     * Holds the in-flight [write] call's continuation while waiting for
     * [BluetoothGattCallback.onCharacteristicWrite] -- GATT only allows one
     * outstanding operation at a time, so there's never more than one of
     * these live.
     */
    private var writeContinuation: CancellableContinuation<Boolean>? = null

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
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            connectToDeviceInternal(device)
        } ?: run {
            // 接続はしたがdiscoverServices()のコールバックが返ってこないまま
            // 固まるケース(安物モジュールでたまに起きる)をここで打ち切る。
            // ここに来た時点でgattが残っていれば後始末する。
            Log.w(TAG, "connectToDevice: timed out after ${CONNECT_TIMEOUT_MS}ms")
            // close() is deferred to onConnectionStateChange(STATE_DISCONNECTED)
            // once Android confirms the disconnect actually completed.
            gatt?.disconnect()
            gatt = null
            null
        }

    @SuppressLint("MissingPermission")
    private suspend fun connectToDeviceInternal(device: BluetoothDevice): BleUartProfile? =
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // 安価なBT05/HC-08/HM-10クローン(CC254x系)は接続直後に
                        // discoverServices()を呼ぶと失敗するかコールバックが
                        // 一切返ってこないことがある(既知の癖)。少し待ってから
                        // 呼ぶことで安定する。
                        Handler(Looper.getMainLooper()).postDelayed(
                            { g.discoverServices() },
                            SERVICE_DISCOVERY_DELAY_MS
                        )
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // Only close() once Android confirms the disconnect
                        // actually completed -- closing right after calling
                        // disconnect() (without waiting for this callback) is
                        // a known cause of GATT_ERROR(133) and erratic
                        // connect failures on the *next* attempt.
                        g.close()
                        if (!resumed) {
                            resumed = true
                            continuation.resume(null, onCancellation = null)
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
                        txCharacteristic = service?.getCharacteristic(profile.txCharUuid)?.apply {
                            // HM-10/HC-08系のUARTモジュールはWRITE_NO_RESPONSEしか
                            // 対応していないことが多く、その場合デフォルトの
                            // WRITE_TYPE_DEFAULT(応答あり)を指定するとwriteCharacteristic()が
                            // 即falseを返して何も送信されない。実際にPROPERTY_WRITEを
                            // サポートしている場合のみ応答ありを使う。
                            writeType = if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            } else {
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            }
                            Log.d(TAG, "tx characteristic properties=$properties chosen writeType=$writeType")
                        }
                        val rxChar = service?.getCharacteristic(profile.rxCharUuid)
                        val descriptor = rxChar?.getDescriptor(CCCD_UUID)
                        if (rxChar != null && descriptor != null) {
                            g.setCharacteristicNotification(rxChar, true)
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            // このwriteDescriptor()の完了(onDescriptorWrite)を待たずに
                            // continuationを再開すると、GATTは1度に1つの操作しか処理
                            // できないため、直後のハンドシェイク送信がこの書き込みと
                            // 衝突してwriteCharacteristic()が即falseを返してしまう
                            // (実機で確認済み)。ここでは待たずに、onDescriptorWrite側で
                            // resumeする。
                            // さらに、サービス検出の直後すぐに書き込むとGATT_ERROR(133)で
                            // 失敗し即切断されることが実機で確認された(discoverServices()
                            // を接続直後すぐ呼んだ時と同じ種類のAndroid BLEスタックの癖)。
                            // 同様に少し待ってから書き込む。
                            Handler(Looper.getMainLooper()).postDelayed(
                                { g.writeDescriptor(descriptor) },
                                CCCD_WRITE_DELAY_MS
                            )
                            return
                        }
                    }
                    if (!resumed) {
                        resumed = true
                        continuation.resume(profile, onCancellation = null)
                    }
                }

                var cccdRetried = false

                @Suppress("DEPRECATION")
                override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    Log.d(TAG, "onDescriptorWrite: status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS && !cccdRetried) {
                        // 133(GATT_ERROR)は安価なクローンモジュールで頻発する一時的な
                        // エラー。1回だけ間を置いて再試行する。
                        cccdRetried = true
                        Handler(Looper.getMainLooper()).postDelayed(
                            { g.writeDescriptor(descriptor) },
                            CCCD_WRITE_DELAY_MS
                        )
                        return
                    }
                    if (!resumed) {
                        resumed = true
                        val matchedProfile = BleUartProfiles.KNOWN_PROFILES.firstOrNull { candidate ->
                            g.getService(candidate.serviceUuid)?.getCharacteristic(candidate.txCharUuid) != null
                        }
                        continuation.resume(matchedProfile, onCancellation = null)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    Log.d(TAG, "onCharacteristicWrite: status=$status")
                    val continuation = writeContinuation ?: return
                    writeContinuation = null
                    continuation.resume(status == BluetoothGatt.GATT_SUCCESS, onCancellation = null)
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
        // writeCharacteristic()'s own return value only means the request was
        // queued -- it says nothing about whether the byte actually made it
        // over the air. Wait for onCharacteristicWrite so a silent BLE-level
        // write failure surfaces as a failed write instead of masquerading as
        // "wrote fine, ECU just didn't echo."
        return withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                writeContinuation = continuation
                val queued = g.writeCharacteristic(characteristic)
                if (!queued) {
                    Log.w(TAG, "write: writeCharacteristic() returned false")
                    writeContinuation = null
                    continuation.resume(false, onCancellation = null)
                }
            }
        } ?: run {
            Log.w(TAG, "write: onCharacteristicWrite never arrived within ${WRITE_TIMEOUT_MS}ms")
            writeContinuation = null
            false
        }
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
        // close() is deferred to onConnectionStateChange(STATE_DISCONNECTED)
        // above, once Android confirms the disconnect actually completed.
        gatt?.disconnect()
        gatt = null
        txCharacteristic = null
    }

    private companion object {
        const val TAG = "RoverMEMS"
        const val SERVICE_DISCOVERY_DELAY_MS = 600L
        const val CCCD_WRITE_DELAY_MS = 400L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 2_000L
    }
}
