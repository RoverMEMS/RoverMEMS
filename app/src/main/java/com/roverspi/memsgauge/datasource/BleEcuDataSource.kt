package com.roverspi.memsgauge.datasource

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.roverspi.memsgauge.logging.DebugLog as Log
import com.roverspi.memsgauge.ble.BleUartProfile
import com.roverspi.memsgauge.ble.BleUartTransport
import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsActuatorCommand
import com.roverspi.memsgauge.protocol.MemsData
import com.roverspi.memsgauge.protocol.MemsProtocol
import com.roverspi.memsgauge.protocol.toHexString
import com.roverspi.memsgauge.usb.UsbSniffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads live data from a real MEMS ECU over a BLE UART bridge module. Mirrors
 * [MockEcuDataSource]'s interface exactly, so the UI never knows which one
 * it's talking to. Call [setDevice] with the device the user picked on the
 * connect screen (see BleScanner) before calling [connect].
 */
class BleEcuDataSource(context: Context) : EcuDataSource {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ecuVersion = MutableStateFlow(EcuVersion.UNKNOWN)
    override val ecuVersion: StateFlow<EcuVersion> = _ecuVersion.asStateFlow()

    private val _ecuIdRaw = MutableStateFlow<String?>(null)
    override val ecuIdRaw: StateFlow<String?> = _ecuIdRaw.asStateFlow()

    private val _latestData = MutableStateFlow<MemsData?>(null)
    override val latestData: StateFlow<MemsData?> = _latestData.asStateFlow()

    private val transport = BleUartTransport(context)
    private val protocol = MemsProtocol(transport)
    private val usbSniffer = UsbSniffer(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var pendingDevice: BluetoothDevice? = null

    // The ECU link is a single request/response serial connection -- the poll
    // loop and any on-demand command (clear faults, actuator test) must never
    // talk to the transport at the same time, or their bytes interleave.
    private val linkMutex = Mutex()

    /** Selects which discovered device to use on the next [connect] call. */
    fun setDevice(device: BluetoothDevice) {
        pendingDevice = device
    }

    /**
     * Safe to call again on an already-connected (or stalled/errored)
     * instance -- also doubles as the manual "reconnect" action from the
     * gauge screen, so it must always leave things in a clean state rather
     * than assuming this is the first call.
     */
    override suspend fun connect() {
        val device = pendingDevice
        if (device == null) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        pollingJob?.cancel()
        pollingJob = null
        transport.disconnect()
        _connectionState.value = ConnectionState.CONNECTING
        val version = performHandshake(device)
        if (version == null) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        _ecuVersion.value = version
        _connectionState.value = ConnectionState.CONNECTED
        transport.onUnexpectedDisconnect = {
            pollingJob?.cancel()
            pollingJob = null
            startAutoReconnect()
        }
        pollingJob = scope.launch { pollLoop(version) }
    }

    /**
     * モジュールはACC電源なので、キーをSTART(セル)まで回すと電源が一瞬
     * 落ちてBLEが切れる(09-28実車: イグニッションON→始動で毎回切断)。
     * エラーで止めずに、モジュールが復帰するまで一定時間つなぎ直しを続ける。
     * キーOFFで切れた場合は復帰しないので、時間切れでエラー表示にする。
     */
    private fun startAutoReconnect() {
        autoReconnectJob?.cancel()
        _connectionState.value = ConnectionState.RECONNECTING
        autoReconnectJob = scope.launch {
            val device = pendingDevice
            if (device == null) {
                _connectionState.value = ConnectionState.ERROR
                return@launch
            }
            val deadline = System.currentTimeMillis() + AUTO_RECONNECT_WINDOW_MS
            var round = 0
            while (System.currentTimeMillis() < deadline) {
                delay(AUTO_RECONNECT_INTERVAL_MS)
                round++
                Log.d(TAG, "autoReconnect: round $round")
                transport.disconnect()
                val version = performHandshake(device)
                if (version != null) {
                    Log.d(TAG, "autoReconnect: succeeded (round $round)")
                    _ecuVersion.value = version
                    _connectionState.value = ConnectionState.CONNECTED
                    pollingJob = scope.launch { pollLoop(version) }
                    return@launch
                }
            }
            Log.e(TAG, "autoReconnect: gave up after ${AUTO_RECONNECT_WINDOW_MS}ms")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /** Runs the connect+handshake sequence; also used by [pollLoop] to recover a stalled link. */
    private suspend fun performHandshake(device: BluetoothDevice): EcuVersion? {
        // USBシリアルケーブルが挿さっていれば、ハンドシェイクの間だけ
        // ECUの白線を盗聴してログに残す(診断用、UsbSniffer参照)
        usbSniffer.start()
        try {
            return performHandshakeInternal(device)
        } finally {
            usbSniffer.stop()
        }
    }

    private suspend fun performHandshakeInternal(device: BluetoothDevice): EcuVersion? {
        // BLE接続そのもの(サービス検出まで)は実車で約4割失敗するが、
        // 09-26〜27のログでは失敗の直後の1回は毎回成功しており、2連続で
        // 失敗した例は一度もない。ユーザーに「エラー」を見せる前に、
        // 少し間を置いて自動で再試行する。
        var profile: BleUartProfile? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            profile = transport.connectToDevice(device)
            if (profile != null) break
            Log.w(TAG, "performHandshake: BLE connect attempt $attempt/$CONNECT_ATTEMPTS failed")
            if (attempt < CONNECT_ATTEMPTS) delay(CONNECT_RETRY_DELAY_MS)
        }
        if (profile == null) {
            Log.w(TAG, "performHandshake: no known BLE UART profile matched")
            return null
        }
        val version = linkMutex.withLock { protocol.initLink() }
        _ecuIdRaw.value = protocol.lastEcuIdBytes?.toHexString()
        if (version == null) {
            Log.w(TAG, "performHandshake: initLink() failed, no ECU response")
            // 切断前に、まとめ送りで応答が変わるかを試してログに残す(診断用)
            linkMutex.withLock { protocol.burstProbe() }
            transport.disconnect()
            return null
        }
        Log.d(TAG, "performHandshake: connected, ecuVersion=$version")
        return version
    }

    override fun disconnect() {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        pollingJob?.cancel()
        pollingJob = null
        transport.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _ecuVersion.value = EcuVersion.UNKNOWN
        _ecuIdRaw.value = null
        _latestData.value = null
    }

    override suspend fun clearFaults(): Boolean = linkMutex.withLock { protocol.clearFaults() }

    override suspend fun runActuatorTest(command: MemsActuatorCommand): Boolean =
        linkMutex.withLock { protocol.testActuator(command) != null }

    /**
     * A "still CONNECTED" state doesn't mean data is actually flowing -- a
     * desynced echo check or an ECU that stops answering both look identical
     * to the transport layer (readData just keeps returning null). This loop
     * tracks how long it's been since the last good frame and escalates:
     * first a cheap flush+resync, then a full reconnect, before giving up.
     */
    private suspend fun pollLoop(initialVersion: EcuVersion) {
        var ecuVersion = initialVersion
        var lastSuccessMs = System.currentTimeMillis()
        while (true) {
            val data = linkMutex.withLock { protocol.readData(ecuVersion) }
            val now = System.currentTimeMillis()
            if (data != null) {
                _latestData.value = data
                lastSuccessMs = now
                if (_connectionState.value == ConnectionState.RECONNECTING) {
                    _connectionState.value = ConnectionState.CONNECTED
                }
            } else {
                val staleMs = now - lastSuccessMs
                if (staleMs >= FULL_RECONNECT_THRESHOLD_MS) {
                    Log.w(TAG, "pollLoop: stalled ${staleMs}ms, attempting full reconnect")
                    val device = pendingDevice
                    transport.disconnect()
                    val newVersion = device?.let { performHandshake(it) }
                    if (newVersion != null) {
                        Log.d(TAG, "pollLoop: reconnect succeeded")
                        ecuVersion = newVersion
                        lastSuccessMs = System.currentTimeMillis()
                        _connectionState.value = ConnectionState.CONNECTED
                    } else {
                        Log.w(TAG, "pollLoop: reconnect failed, handing over to autoReconnect")
                        pollingJob = null
                        startAutoReconnect()
                        return
                    }
                } else if (staleMs >= FLUSH_RESYNC_THRESHOLD_MS) {
                    Log.w(TAG, "pollLoop: stalled ${staleMs}ms, flushing and resyncing")
                    _connectionState.value = ConnectionState.RECONNECTING
                    linkMutex.withLock {
                        transport.flushStaleBytes()
                        protocol.heartbeat()
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private companion object {
        const val TAG = "RoverMEMS"
        // BLEは1回の取得自体に数百msかかるので、間の待ちは短くして更新回数を
        // 稼ぐ(200→50ms、10-02)。ゼロにしないのは、エラークリア・部品テストが
        // linkMutexを取れる隙間を残すため。
        const val POLL_INTERVAL_MS = 50L
        const val FLUSH_RESYNC_THRESHOLD_MS = 4_000L
        const val FULL_RECONNECT_THRESHOLD_MS = 8_000L
        const val CONNECT_ATTEMPTS = 3
        const val CONNECT_RETRY_DELAY_MS = 1_000L
        const val AUTO_RECONNECT_WINDOW_MS = 60_000L
        const val AUTO_RECONNECT_INTERVAL_MS = 1_500L
    }
}
