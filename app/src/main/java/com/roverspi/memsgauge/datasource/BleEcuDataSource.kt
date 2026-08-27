package com.roverspi.memsgauge.datasource

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.roverspi.memsgauge.ble.BleUartTransport
import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsActuatorCommand
import com.roverspi.memsgauge.protocol.MemsData
import com.roverspi.memsgauge.protocol.MemsProtocol
import com.roverspi.memsgauge.protocol.toHexString
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
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
            _connectionState.value = ConnectionState.ERROR
            pollingJob?.cancel()
        }
        pollingJob = scope.launch { pollLoop(version) }
    }

    /** Runs the connect+handshake sequence; also used by [pollLoop] to recover a stalled link. */
    private suspend fun performHandshake(device: BluetoothDevice): EcuVersion? {
        val profile = transport.connectToDevice(device)
        if (profile == null) {
            Log.w(TAG, "performHandshake: no known BLE UART profile matched")
            return null
        }
        val version = linkMutex.withLock { protocol.initLink() }
        _ecuIdRaw.value = protocol.lastEcuIdBytes?.toHexString()
        if (version == null) {
            Log.w(TAG, "performHandshake: initLink() failed, no ECU response")
            transport.disconnect()
            return null
        }
        Log.d(TAG, "performHandshake: connected, ecuVersion=$version")
        return version
    }

    override fun disconnect() {
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
                        Log.e(TAG, "pollLoop: reconnect failed, giving up")
                        _connectionState.value = ConnectionState.ERROR
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
        const val POLL_INTERVAL_MS = 200L
        const val FLUSH_RESYNC_THRESHOLD_MS = 4_000L
        const val FULL_RECONNECT_THRESHOLD_MS = 8_000L
    }
}
