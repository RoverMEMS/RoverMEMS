package com.roverspi.memsgauge.protocol

/**
 * Raw 32-byte frame returned by the ECU in reply to [MemsDataCommand.REQ_DATA_7D].
 * Field layout ported from librosco's rosco.h (mems_data_frame_7d). Byte count
 * verified by counting the struct's fields directly (librosco's own comments
 * say 31, but the struct has 32 uint8_t members).
 */
data class MemsFrame7d(
    val bytesInFrame: Int,
    val ignitionSwitchState: Int,
    val throttleAngleRaw: Int,        // *0.6 for degrees
    val unknown4: Int,
    val airFuelRatioRaw: Int,         // /10.0 for ratio
    val dtc2: Int,
    val lambdaVoltageRaw: Int,        // *5 for millivolts
    val lambdaFreq: Int,
    val lambdaDutyCycle: Int,
    val lambdaStatus: Int,
    val closedLoop: Int,
    val longTermFuelTrim: Int,
    val shortTermFuelTrim: Int,
    val carbonCanisterDutyCycle: Int,
    val dtc3: Int,
    val idleBasePos: Int,
    val unknown5: Int,
    val dtc4: Int,
    val ignitionAdvance2: Int,
    val idleSpeedOffset: Int,
    val idleError2: Int,
    /** Offsets 21..31 (11 bytes): unknown6..unknown10 in librosco, meaning unconfirmed. */
    val unknownTail: List<Int>
) {
    companion object {
        const val FRAME_SIZE = 32
        private const val UNKNOWN_TAIL_SIZE = 11

        fun parse(bytes: ByteArray): MemsFrame7d {
            require(bytes.size == FRAME_SIZE) {
                "Expected $FRAME_SIZE bytes for a 0x7D frame, got ${bytes.size}"
            }
            fun u(i: Int) = bytes[i].toInt() and 0xFF
            return MemsFrame7d(
                bytesInFrame = u(0),
                ignitionSwitchState = u(1),
                throttleAngleRaw = u(2),
                unknown4 = u(3),
                airFuelRatioRaw = u(4),
                dtc2 = u(5),
                lambdaVoltageRaw = u(6),
                lambdaFreq = u(7),
                lambdaDutyCycle = u(8),
                lambdaStatus = u(9),
                closedLoop = u(10),
                longTermFuelTrim = u(11),
                shortTermFuelTrim = u(12),
                carbonCanisterDutyCycle = u(13),
                dtc3 = u(14),
                idleBasePos = u(15),
                unknown5 = u(16),
                dtc4 = u(17),
                ignitionAdvance2 = u(18),
                idleSpeedOffset = u(19),
                idleError2 = u(20),
                unknownTail = (0 until UNKNOWN_TAIL_SIZE).map { i -> u(21 + i) }
            )
        }
    }
}
