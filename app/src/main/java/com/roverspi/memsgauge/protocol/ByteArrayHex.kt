package com.roverspi.memsgauge.protocol

/** Formats bytes as a space-separated uppercase hex string, e.g. "99 00 03 03". */
fun ByteArray.toHexString(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
