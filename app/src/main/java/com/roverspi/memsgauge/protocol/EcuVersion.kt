package com.roverspi.memsgauge.protocol

/**
 * ECU generation, identified from the 4-byte response to the 0xD0 command
 * sent during the init-link handshake (see [MemsProtocol.initLink]).
 *
 * Only IDs confirmed on a real car are mapped. The D0 response looks more
 * like a per-calibration ID than a 1.3-vs-1.6 marker (librosco calls its
 * 99 00 03 03 Mini SPi "MEMS 1.6"), so anything else stays UNKNOWN and the
 * UI shows the raw ID instead of guessing. Frame parsing doesn't depend on it.
 */
enum class EcuVersion {
    MEMS_1_3,
    UNKNOWN;

    companion object {
        // 1996 Mini SPi (MEMS 1.3). Seen on the real car over USB and BLE, 2026-09/10.
        private val MEMS_1_3_ID_MINI_1996 = byteArrayOf(0x9A.toByte(), 0x00, 0x02, 0x02)

        fun fromD0Response(bytes: ByteArray): EcuVersion = when {
            bytes.contentEquals(MEMS_1_3_ID_MINI_1996) -> MEMS_1_3
            else -> UNKNOWN
        }
    }
}
