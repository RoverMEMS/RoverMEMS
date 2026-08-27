package com.roverspi.memsgauge.protocol

/**
 * Byte-level transport used by [MemsProtocol]. Implemented separately for the
 * BLE UART bridge and for the simulator, so the protocol logic never needs to
 * know which one it's talking to.
 */
interface ByteTransport {
    /** Writes [bytes] to the link. Returns true if the write completed fully. */
    suspend fun write(bytes: ByteArray): Boolean

    /**
     * Reads exactly [count] bytes, waiting up to [timeoutMs] in total.
     * Returns null if [count] bytes did not arrive within the timeout.
     */
    suspend fun readExactly(count: Int, timeoutMs: Long = 500): ByteArray?

    /**
     * Discards any bytes already buffered from the link without waiting for
     * more. The MEMS link is strictly request/response, so nothing should be
     * sitting in the buffer right before a new command is sent -- unless an
     * earlier [readExactly] call timed out and its (late-arriving) response
     * bytes are still queued. Call this before sending a new command so those
     * stale bytes don't get consumed as part of the new command's echo/response,
     * which would permanently shift every read afterward by that many bytes.
     */
    fun flushStaleBytes() {}
}
