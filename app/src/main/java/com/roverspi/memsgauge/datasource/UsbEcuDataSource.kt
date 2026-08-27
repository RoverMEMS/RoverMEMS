package com.roverspi.memsgauge.datasource

import android.content.Context
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsActuatorCommand
import com.roverspi.memsgauge.protocol.MemsData
import com.roverspi.memsgauge.protocol.MemsProtocol
import com.roverspi.memsgauge.protocol.toHexString
import com.roverspi.memsgauge.usb.UsbDeviceScanner
import com.roverspi.memsgauge.usb.UsbSerialTransport
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
 * Reads live data from a real MEMS ECU over the wired USB-serial cable the
 * user already built (matching the Windows MEMSGauge app's FTDI USB-to-TTL
 * setup, plugged into the phone/tablet via USB-OTG). Mirrors
 * [MockEcuDataSource]/[BleEcuDataSource]'s interface exactly, so the UI
 * doesn't need to know which transport is active.
 */
class UsbEcuDataSource(context: Context) : EcuDataSource {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ecuVersion = MutableStateFlow(EcuVersion.UNKNOWN)
    override val ecuVersion: StateFlow<EcuVersion> = _ecuVersion.asStateFlow()

    private val _ecuIdRaw = MutableStateFlow<String?>(null)
    override val ecuIdRaw: StateFlow<String?> = _ecuIdRaw.asStateFlow()

    private val _latestData = MutableStateFlow<MemsData?>(null)
    override val latestData: StateFlow<MemsData?> = _latestData.asStateFlow()

    private val scanner = UsbDeviceScanner(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var transport: UsbSerialTransport? = null
    private var protocol: MemsProtocol? = null
    private var pendingDriver: UsbSerialDriver? = null

    // The ECU link is a single request/response serial connection -- the poll
    // loop and any on-demand command (clear faults, actuator test) must never
    // talk to the transport at the same time, or their bytes interleave.
    private val linkMutex = Mutex()

    /** Lists USB-serial adapters currently plugged in (FTDI/CP210x/CH340/PL2303). */
    fun listAvailableDrivers(): List<UsbSerialDriver> = scanner.listAvailableDrivers()

    /** Selects which USB-serial driver/device to use on the next [connect] call. */
    fun setDriver(driver: UsbSerialDriver) {
        pendingDriver = driver
    }

    /**
     * Safe to call again on an already-connected (or stalled/errored)
     * instance -- also doubles as the manual "reconnect" action from the
     * gauge screen, so it must always leave things in a clean state rather
     * than assuming this is the first call.
     */
    override suspend fun connect() {
        val driver = pendingDriver
        if (driver == null) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        pollingJob?.cancel()
        pollingJob = null
        transport?.close()
        transport = null
        protocol = null
        _connectionState.value = ConnectionState.CONNECTING
        val version = performHandshake(driver)
        if (version == null) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        _ecuVersion.value = version
        _connectionState.value = ConnectionState.CONNECTED
        pollingJob = scope.launch { pollLoop(version) }
    }

    /** Runs the connect+handshake sequence; also used by [pollLoop] to recover a stalled link. */
    private suspend fun performHandshake(driver: UsbSerialDriver): EcuVersion? {
        if (!scanner.requestPermission(driver.device)) {
            Log.w(TAG, "performHandshake: USB permission denied")
            return null
        }

        val connection = scanner.openConnection(driver.device)
        val port = driver.ports.firstOrNull()
        if (connection == null || port == null) {
            Log.w(TAG, "performHandshake: no USB connection/port available")
            return null
        }

        val newTransport = UsbSerialTransport(port)
        try {
            newTransport.open(connection)
        } catch (e: Exception) {
            Log.e(TAG, "performHandshake: UsbSerialTransport.open() failed", e)
            return null
        }
        transport = newTransport

        val newProtocol = MemsProtocol(newTransport)
        val version = linkMutex.withLock { newProtocol.initLink() }
        _ecuIdRaw.value = newProtocol.lastEcuIdBytes?.toHexString()
        if (version == null) {
            Log.w(TAG, "performHandshake: initLink() failed, no ECU response")
            newTransport.close()
            transport = null
            return null
        }
        newTransport.onFatalError = {
            _connectionState.value = ConnectionState.ERROR
            pollingJob?.cancel()
        }
        protocol = newProtocol
        Log.d(TAG, "performHandshake: connected, ecuVersion=$version")
        return version
    }

    override fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        transport?.close()
        transport = null
        protocol = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _ecuVersion.value = EcuVersion.UNKNOWN
        _ecuIdRaw.value = null
        _latestData.value = null
    }

    override suspend fun clearFaults(): Boolean {
        val currentProtocol = protocol ?: return false
        return linkMutex.withLock { currentProtocol.clearFaults() }
    }

    override suspend fun runActuatorTest(command: MemsActuatorCommand): Boolean {
        val currentProtocol = protocol ?: return false
        return linkMutex.withLock { currentProtocol.testActuator(command) != null }
    }

    /**
     * A "still CONNECTED" state doesn't mean data is actually flowing -- a
     * silently-dead read loop or an ECU that stops answering both look
     * identical to the transport layer (readData just keeps returning null).
     * This loop tracks how long it's been since the last good frame and
     * escalates: first a cheap flush+resync, then a full reconnect (closing
     * and reopening the serial port), before giving up.
     */
    private suspend fun pollLoop(initialVersion: EcuVersion) {
        var ecuVersion = initialVersion
        var lastSuccessMs = System.currentTimeMillis()
        while (true) {
            val currentProtocol = protocol ?: return
            val data = linkMutex.withLock { currentProtocol.readData(ecuVersion) }
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
                    val driver = pendingDriver
                    transport?.close()
                    val newVersion = driver?.let { performHandshake(it) }
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
                        transport?.flushStaleBytes()
                        currentProtocol.heartbeat()
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
