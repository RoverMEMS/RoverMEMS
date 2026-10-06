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
import com.roverspi.memsgauge.logging.describeBytes
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

    /**
     * 接続ごとに、最初の[RAW_NOTIFY_LOG_LIMIT]個の受信通知を中身ごと(16進+文字)
     * ログに残す。実車で0xCAのエコーの代わりに0x54('T')/0x0D/0x0A('\r','\n')
     * が返ってくる件の切り分け用 -- 1バイト目しか見ていないと、それが単発の
     * ゴミなのか、モジュールが吐いている文字列の先頭なのか区別できない。
     * ライブデータ取得中にログが膨れないよう件数を絞っている。
     */
    @Volatile
    private var rawNotifyLogRemaining = 0

    @SuppressLint("MissingPermission")
    suspend fun connectToDevice(device: BluetoothDevice): BleUartProfile? =
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            rawNotifyLogRemaining = RAW_NOTIFY_LOG_LIMIT
            connectToDeviceInternal(device)
        } ?: run {
            // 接続はしたがdiscoverServices()のコールバックが返ってこないまま
            // 固まるケース(安物モジュールでたまに起きる)をここで打ち切る。
            // ここに来た時点でgattが残っていれば後始末する。
            Log.w(TAG, "connectToDevice: timed out after ${CONNECT_TIMEOUT_MS}ms")
            // close() is deferred to onConnectionStateChange(STATE_DISCONNECTED)
            // once Android confirms the disconnect actually completed.
            // ただし接続が確立しきらないまま打ち切った場合はそのコールバックが
            // 来ないことがあり、閉じられないgattが裏で接続を試み続けて次の
            // 再試行の邪魔をしうるので、少し待ってから念のため閉じる。
            val stale = gatt
            stale?.disconnect()
            gatt = null
            if (stale != null) {
                Handler(Looper.getMainLooper()).postDelayed({ stale.close() }, STALE_GATT_CLOSE_DELAY_MS)
            }
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
                        } else if (g !== gatt) {
                            // こちらからdisconnect()した(gattはその時点でnull
                            // または次の接続のものに置き換わっている)接続の
                            // 切断完了通知。意図した切断なので自動再接続させない。
                            Log.d(TAG, "BLE disconnect completed (status=$status)")
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
                    // 接続間隔を最短(7.5〜15ms)に要求する。既定(30〜50ms程度)だと
                    // 「エコー受信→次のコマンド送信」の間隔が約113msに伸び、ECUが
                    // 初期化シーケンスを受け付けなかった(09-27、これで約76msになり
                    // 初接続成功)。接続直後(STATE_CONNECTED時点)に要求すると
                    // status=40で切れる例があったため、サービス検出後に行う。
                    val accepted = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Log.d(TAG, "requestConnectionPriority(HIGH) accepted=$accepted")
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
                    if (g !== gatt) return // 古い接続からの受信は捨てる
                    val value = characteristic.value ?: return
                    if (rawNotifyLogRemaining > 0) {
                        rawNotifyLogRemaining--
                        Log.d(TAG, "rx notify (${value.size} bytes): ${describeBytes(value)}")
                    }
                    value.forEach { byte -> incomingBytes.trySendBlocking(byte) }
                }
            }
            // 前の接続が残っていると、新しい接続と同時にモジュールへ二重につながり
            // 受信データが2回ずつ届く(10-06朝の不調)。必ず先に閉じてから始める。
            gatt?.let { old ->
                old.disconnect()
                Handler(Looper.getMainLooper()).postDelayed({ old.close() }, STALE_GATT_CLOSE_DELAY_MS)
            }
            val created = device.connectGatt(context, false, callback)
            gatt = created
            // 呼び出し側がキャンセル/タイムアウトした時は、この接続を必ず切る
            // (放置すると裏でつながり続けて次の接続と二重になる)。
            continuation.invokeOnCancellation {
                created.disconnect()
                Handler(Looper.getMainLooper()).postDelayed({ created.close() }, STALE_GATT_CLOSE_DELAY_MS)
                if (gatt === created) gatt = null
            }
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
        val discarded = mutableListOf<Byte>()
        while (true) {
            discarded.add(incomingBytes.tryReceive().getOrNull() ?: break)
        }
        if (discarded.isNotEmpty()) {
            Log.d(TAG, "flushStaleBytes: discarded ${discarded.size} bytes: ${describeBytes(discarded.toByteArray())}")
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
        const val RAW_NOTIFY_LOG_LIMIT = 50
        const val SERVICE_DISCOVERY_DELAY_MS = 600L
        const val CCCD_WRITE_DELAY_MS = 400L
        // 成功時は接続〜通知有効化まで約2秒。以前は15秒待ってからエラーに
        // していたが、BleEcuDataSource側で自動再試行するので早めに見切る。
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val STALE_GATT_CLOSE_DELAY_MS = 500L
        const val WRITE_TIMEOUT_MS = 2_000L
    }
}
