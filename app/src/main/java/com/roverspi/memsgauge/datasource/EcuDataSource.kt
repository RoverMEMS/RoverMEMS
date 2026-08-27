package com.roverspi.memsgauge.datasource

import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsActuatorCommand
import com.roverspi.memsgauge.protocol.MemsData
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

/**
 * Source of live ECU data. Implemented by [MockEcuDataSource] (simulated
 * data, no hardware needed), [BleEcuDataSource] (real ECU over a BLE UART
 * bridge), and [UsbEcuDataSource] (real ECU over a wired USB-serial adapter).
 * The UI/ViewModel layer only ever depends on this interface, so switching
 * between simulator and real hardware never touches UI code.
 */
interface EcuDataSource {
    val connectionState: StateFlow<ConnectionState>
    val ecuVersion: StateFlow<EcuVersion>
    val latestData: StateFlow<MemsData?>

    /**
     * The raw 4-byte 0xD0 ECU ID response as a hex string (e.g. "99 00 03 03"),
     * or null before a connection attempt. Populated even when [ecuVersion]
     * comes back UNKNOWN, so the UI can show what a real, unrecognized ECU
     * actually sent instead of just an "unknown" dead end.
     */
    val ecuIdRaw: StateFlow<String?>

    suspend fun connect()
    fun disconnect()

    /** Sends the fault-code-clear command (0xCC). Returns true on success. */
    suspend fun clearFaults(): Boolean

    /**
     * Runs a single actuator test command (fuel pump, relays, injector,
     * coil, etc. -- see [MemsActuatorCommand]). Callers are responsible for
     * only allowing this while the engine is stopped, matching MEMSGauge's
     * and MEMSFCR's safety behavior. Returns true on success.
     */
    suspend fun runActuatorTest(command: MemsActuatorCommand): Boolean
}
