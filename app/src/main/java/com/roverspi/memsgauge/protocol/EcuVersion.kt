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
        private val MEMS_1_6_ID = byteArrayOf(0x99.toByte(), 0x00, 0x02, 0x03)

        fun fromD0Response(bytes: ByteArray): EcuVersion = when {
            bytes.contentEquals(MEMS_1_3_ID) -> MEMS_1_3
            bytes.contentEquals(MEMS_1_6_ID) -> MEMS_1_6
            else -> UNKNOWN
        }
    }
}
