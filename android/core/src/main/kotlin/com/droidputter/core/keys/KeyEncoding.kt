package com.droidputter.core.keys

import com.droidputter.core.protocol.DP_SYNC0
import com.droidputter.core.protocol.DP_SYNC1
import com.droidputter.core.protocol.dpCrc8

/** phone -> ESP KEY frame type, docs/PROTOCOL.md "phone -> ESP types". */
const val DP_TYPE_KEY: Int = 0x81

/**
 * Encodes a full wire frame for KEY {row u8, col u8, state u8 (1 down / 0 up)}, byte-exact with
 * docs/PROTOCOL.md's "phone -> ESP: KEY down/up for key '1' at (row=0, col=1)" worked example.
 */
fun encodeKey(row: Int, col: Int, down: Boolean): ByteArray {
    val payload = byteArrayOf(row.toByte(), col.toByte(), if (down) 1 else 0)
    val header = byteArrayOf(DP_TYPE_KEY.toByte(), payload.size.toByte(), 0)
    val crcInput = header + payload
    val crc = dpCrc8(crcInput).toByte()
    return byteArrayOf(DP_SYNC0.toByte(), DP_SYNC1.toByte()) + crcInput + crc
}
