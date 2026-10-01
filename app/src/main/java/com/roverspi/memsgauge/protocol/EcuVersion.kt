package com.roverspi.memsgauge.protocol

/**
 * ECU generation, identified from the 4-byte response to the 0xD0 command
 * sent during the init-link handshake (see [MemsProtocol.initLink]).
 */
enum class EcuVersion {
    MEMS_1_3,
    MEMS_1_6,
    UNKNOWN;

    companion object {
        private val MEMS_1_3_ID = byteArrayOf(0x99.toByte(), 0x00, 0x03, 0x03)
        // 1996 Mini SPi (MEMS 1.3). Seen on the real car over USB and BLE, 2026-09/10.
        private val MEMS_1_3_ID_MINI_1996 = byteArrayOf(0x9A.toByte(), 0x00, 0x02, 0x02)
        private val MEMS_1_6_ID = byteArrayOf(0x99.toByte(), 0x00, 0x02, 0x03)

        fun fromD0Response(bytes: ByteArray): EcuVersion = when {
            bytes.contentEquals(MEMS_1_3_ID) -> MEMS_1_3
            bytes.contentEquals(MEMS_1_3_ID_MINI_1996) -> MEMS_1_3
            bytes.contentEquals(MEMS_1_6_ID) -> MEMS_1_6
            else -> UNKNOWN
        }
    }
}
