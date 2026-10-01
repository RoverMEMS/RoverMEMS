package com.roverspi.memsgauge.logging

/** "54 0D 0A | T.." のように16進と、表示できる文字(それ以外は'.')を並べる。 */
fun describeBytes(bytes: ByteArray): String {
    val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    val ascii = bytes.map { b ->
        val c = b.toInt() and 0xFF
        if (c in 0x20..0x7E) c.toChar() else '.'
    }.joinToString("")
    return "$hex | $ascii"
}
