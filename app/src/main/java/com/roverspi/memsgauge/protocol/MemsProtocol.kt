package com.roverspi.memsgauge.protocol

import android.util.Log

/**
 * Ports librosco's protocol.c command sequences onto a [ByteTransport].
 * The link is half-duplex: every command byte sent is expected to be
 * echoed back by the ECU before any further response data follows.
 */
class MemsProtocol(private val transport: ByteTransport) {

    private companion object {
        const val TAG = "RoverMEMS"
        const val INIT_BYTE_A = 0xCA
        const val INIT_BYTE_B = 0x75
        const val ID_REQUEST = 0xD0
        const val ID_RESPONSE_SIZE = 4
    }

    /**
     * The raw 4-byte 0xD0 response from the most recent [initLink] call, kept
     * around so the UI can show it when [EcuVersion.fromD0Response] can't
     * match it to a known version -- lets us capture what a real, unrecognized
     * ECU actually sends instead of guessing.
     */
    var lastEcuIdBytes: ByteArray? = null
        private set

    /**
     * Sends the ECU startup sequence (0xCA, 0x75, heartbeat, 0xD0) and
     * returns the detected ECU version from the 0xD0 response, or null if
     * any step of the handshake failed.
     */
    suspend fun initLink(): EcuVersion? {
        if (!sendCommand(INIT_BYTE_A)) {
            Log.w(TAG, "initLink: INIT_BYTE_A (0xCA) got no valid echo")
            return null
        }
        if (!sendCommand(INIT_BYTE_B)) {
            Log.w(TAG, "initLink: INIT_BYTE_B (0x75) got no valid echo")
            return null
        }
        if (!sendCommand(MemsDataCommand.HEARTBEAT.byte)) {
            Log.w(TAG, "initLink: heartbeat command got no valid echo")
            return null
        }
        // One byte follows the heartbeat echo; discard it, as librosco does.
        if (transport.readExactly(1) == null) {
            Log.w(TAG, "initLink: heartbeat follow-up byte never arrived")
            return null
        }
        if (!sendCommand(ID_REQUEST)) {
            Log.w(TAG, "initLink: ID_REQUEST (0xD0) got no valid echo")
            return null
        }
        val idResponse = transport.readExactly(ID_RESPONSE_SIZE)
        if (idResponse == null) {
            Log.w(TAG, "initLink: ECU ID response never arrived")
            return null
        }
        lastEcuIdBytes = idResponse
        val version = EcuVersion.fromD0Response(idResponse)
        Log.d(TAG, "initLink: succeeded, ecuVersion=$version")
        return version
    }

    /**
     * Sends a single command byte and confirms the ECU echoes it back.
     * Any additional response data must be read separately.
     */
    suspend fun sendCommand(cmd: Int): Boolean {
        // Clear out anything left over from an earlier call that timed out --
        // otherwise those stale bytes get read as this command's echo/response
        // and every subsequent read stays shifted out of alignment.
        transport.flushStaleBytes()
        if (!transport.write(byteArrayOf(cmd.toByte()))) {
            Log.w(TAG, "sendCommand(0x%02X): write failed".format(cmd))
            return false
        }
        val echo = transport.readExactly(1)
        if (echo == null) {
            Log.w(TAG, "sendCommand(0x%02X): no echo within timeout".format(cmd))
            return false
        }
        val echoByte = echo[0].toInt() and 0xFF
        if (echoByte != cmd) {
            Log.w(TAG, "sendCommand(0x%02X): echo mismatch, got 0x%02X".format(cmd, echoByte))
            return false
        }
        return true
    }

    /** Requests both data frames and merges them into a [MemsData] snapshot. */
    suspend fun readData(ecuVersion: EcuVersion): MemsData? {
        if (!sendCommand(MemsDataCommand.REQ_DATA_80.byte)) return null
        val bytes80 = transport.readExactly(MemsFrame80.FRAME_SIZE)
        if (bytes80 == null) {
            Log.w(TAG, "readData: frame80 body never arrived")
            return null
        }
        val frame80 = MemsFrameParser.parseFrame80(bytes80, ecuVersion)

        if (!sendCommand(MemsDataCommand.REQ_DATA_7D.byte)) return null
        val bytes7d = transport.readExactly(MemsFrame7d.FRAME_SIZE)
        if (bytes7d == null) {
            Log.w(TAG, "readData: frame7d body never arrived")
            return null
        }
        val frame7d = MemsFrameParser.parseFrame7d(bytes7d, ecuVersion)

        return MemsData.fromFrames(frame80, frame7d, ecuVersion)
    }

    /** Sends the fault-code-clear command and confirms the ECU's one-byte reply. */
    suspend fun clearFaults(): Boolean {
        if (!sendCommand(MemsDataCommand.CLEAR_FAULTS.byte)) return false
        return transport.readExactly(1) != null
    }

    /** Simple ping to check the link is still alive. */
    suspend fun heartbeat(): Boolean {
        if (!sendCommand(MemsDataCommand.HEARTBEAT.byte)) return false
        return transport.readExactly(1) != null
    }

    /** Reads the current idle air control motor position. */
    suspend fun readIacPosition(): Int? {
        if (!sendCommand(MemsDataCommand.GET_IAC_POSITION.byte)) return null
        val response = transport.readExactly(1) ?: return null
        return response[0].toInt() and 0xFF
    }

    /**
     * Runs an actuator test (fuel pump, relays, IAC, etc.) and returns the
     * single data byte the ECU sends back. Not used by the v1 UI, but ready
     * for a v2 actuator-test screen.
     */
    suspend fun testActuator(cmd: MemsActuatorCommand): Int? {
        if (!sendCommand(cmd.byte)) return null
        val response = transport.readExactly(1) ?: return null
        return response[0].toInt() and 0xFF
    }
}
