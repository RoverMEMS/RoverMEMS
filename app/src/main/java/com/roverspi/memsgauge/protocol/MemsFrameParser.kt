package com.roverspi.memsgauge.protocol

/**
 * Single seam for MEMS1.3-vs-1.6 frame differences. The layout in
 * [MemsFrame80]/[MemsFrame7d] is confirmed for MEMS1.6 wire format per
 * librosco; MEMS1.3 is assumed identical until verified against real
 * hardware. If real-car testing reveals a different MEMS1.3 layout, only
 * this object needs a new branch — [MemsProtocol], the data sources, and the
 * UI never see raw frame bytes, only the merged [MemsData].
 */
object MemsFrameParser {
    @Suppress("UNUSED_PARAMETER")
    fun parseFrame80(bytes: ByteArray, ecuVersion: EcuVersion): MemsFrame80 =
        MemsFrame80.parse(bytes)

    @Suppress("UNUSED_PARAMETER")
    fun parseFrame7d(bytes: ByteArray, ecuVersion: EcuVersion): MemsFrame7d =
        MemsFrame7d.parse(bytes)
}
