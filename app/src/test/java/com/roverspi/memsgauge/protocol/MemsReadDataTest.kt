package com.roverspi.memsgauge.protocol

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 92年式で「データ待機中」のまま動かない報告(10-12)の対策のテスト。
 * フレーム長は先頭バイトに従って読み、0x7Dに答えないECUでも0x80だけで動くこと。
 */
class MemsReadDataTest {

    /** コマンドごとに、決めた返事を返す偽のECU。送られたコマンドはそのままエコーする。 */
    private class FakeEcu(private val replies: Map<Int, ByteArray?>) : ByteTransport {
        private val queue = ArrayDeque<Byte>()
        val sent = mutableListOf<Int>()

        override suspend fun write(bytes: ByteArray): Boolean {
            for (b in bytes) {
                val cmd = b.toInt() and 0xFF
                sent += cmd
                queue.addLast(b) // エコー
                replies[cmd]?.forEach { queue.addLast(it) }
            }
            return true
        }

        override suspend fun readExactly(count: Int, timeoutMs: Long): ByteArray? {
            if (queue.size < count) { queue.clear(); return null }
            return ByteArray(count) { queue.removeFirst() }
        }

        override fun flushStaleBytes() { queue.clear() }
    }

    private fun frame(size: Int, fill: (ByteArray) -> Unit = {}): ByteArray {
        val b = ByteArray(size)
        b[0] = size.toByte()
        fill(b)
        return b
    }

    private fun frame80(size: Int = 28) = frame(size) { it[1] = 0x04; it[2] = 0xB0.toByte(); it[3] = 140.toByte() } // 1200rpm, 85C
    private fun frame7d(size: Int = 32) = frame(size) { if (size > 12) { it[11] = 128.toByte(); it[12] = 100.toByte() } }

    @Test
    fun normalEcu_readsBothFrames() = runBlocking {
        val ecu = FakeEcu(mapOf(0x80 to frame80(), 0x7D to frame7d()))
        val data = MemsProtocol(ecu).readData(EcuVersion.MEMS_1_3)
        assertNotNull(data)
        assertEquals(1200, data!!.engineRpm)
        assertEquals(85, data.coolantTempC)
        assertTrue(data.frame7dAvailable)
    }

    @Test
    fun shorterFrame80_isStillRead() = runBlocking {
        val ecu = FakeEcu(mapOf(0x80 to frame80(size = 22), 0x7D to frame7d()))
        val data = MemsProtocol(ecu).readData(EcuVersion.MEMS_1_3)
        assertNotNull(data)
        assertEquals(1200, data!!.engineRpm)
    }

    @Test
    fun shorterFrame7d_isStillRead() = runBlocking {
        val ecu = FakeEcu(mapOf(0x80 to frame80(), 0x7D to frame7d(size = 24)))
        val data = MemsProtocol(ecu).readData(EcuVersion.MEMS_1_3)
        assertNotNull(data)
        assertTrue(data!!.frame7dAvailable)
    }

    @Test
    fun ecuThatIgnores7d_stillGivesData_andStopsAsking() = runBlocking {
        val ecu = FakeEcu(mapOf(0x80 to frame80())) // 0x7Dにはエコーも返事もない(エコーだけは返る想定でも可)
        val protocol = MemsProtocol(ecu)
        repeat(4) {
            val data = protocol.readData(EcuVersion.MEMS_1_3)
            assertNotNull("cycle $it", data)
            assertEquals(1200, data!!.engineRpm)
            assertFalse(data.frame7dAvailable)
        }
        // 2回失敗したあとは0x7Dを送らない
        assertEquals(2, ecu.sent.count { it == 0x7D })
    }

    @Test
    fun implausibleSize_isRejected() = runBlocking {
        val ecu = FakeEcu(mapOf(0x80 to frame80().also { it[0] = 3 }))
        assertNull(MemsProtocol(ecu).readData(EcuVersion.MEMS_1_3))
    }
}
