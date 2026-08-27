package com.roverspi.memsgauge.protocol

/**
 * Raw 28-byte frame returned by the ECU in reply to [MemsDataCommand.REQ_DATA_80].
 * Field layout ported from librosco's rosco.h (mems_data_frame_80). Byte count
 * verified by counting the struct's fields directly (librosco's own comments
 * say 29, but the struct only has 28 uint8_t members).
 */
data class MemsFrame80(
    val bytesInFrame: Int,
    val engineRpmHi: Int,
    val engineRpmLo: Int,
    val coolantTemp: Int,
    val ambientTemp: Int,
    val intakeAirTemp: Int,
    val fuelTemp: Int,
    val mapKpa: Int,
    val batteryVoltageRaw: Int,       // divide by 10.0 for volts
    val throttlePotRaw: Int,          // multiply by 0.02 for volts
    val idleSwitch: Int,
    val unknown0: Int,
    val parkNeutralSwitch: Int,
    val dtc0: Int,
    val dtc1: Int,
    val idleSetpoint: Int,
    val idleHot: Int,
    val unknown1: Int,
    val iacPosition: Int,
    val idleErrorHi: Int,
    val idleErrorLo: Int,
    val ignitionAdvanceOffset: Int,
    val ignitionAdvanceRaw: Int,      // *0.5 - 24.0 for degrees
    val coilTimeHi: Int,
    val coilTimeLo: Int,
    val crankshaftPos: Int,
    val unknown2: Int,
    val unknown3: Int
) {
    companion object {
        const val FRAME_SIZE = 28

        fun parse(bytes: ByteArray): MemsFrame80 {
            require(bytes.size == FRAME_SIZE) {
                "Expected $FRAME_SIZE bytes for a 0x80 frame, got ${bytes.size}"
            }
            fun u(i: Int) = bytes[i].toInt() and 0xFF
            return MemsFrame80(
                bytesInFrame = u(0),
                engineRpmHi = u(1),
                engineRpmLo = u(2),
                coolantTemp = u(3),
                ambientTemp = u(4),
                intakeAirTemp = u(5),
                fuelTemp = u(6),
                mapKpa = u(7),
                batteryVoltageRaw = u(8),
                throttlePotRaw = u(9),
                idleSwitch = u(10),
                unknown0 = u(11),
                parkNeutralSwitch = u(12),
                dtc0 = u(13),
                dtc1 = u(14),
                idleSetpoint = u(15),
                idleHot = u(16),
                unknown1 = u(17),
                iacPosition = u(18),
                idleErrorHi = u(19),
                idleErrorLo = u(20),
                ignitionAdvanceOffset = u(21),
                ignitionAdvanceRaw = u(22),
                coilTimeHi = u(23),
                coilTimeLo = u(24),
                crankshaftPos = u(25),
                unknown2 = u(26),
                unknown3 = u(27)
            )
        }
    }
}
