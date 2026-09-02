package com.droidputter.core.protocol

/**
 * Splits a raw byte stream into [Frame]s per docs/PROTOCOL.md: sync 0xD7 0x50, type u8,
 * length u16 LE, payload, crc8. Feed bytes as they arrive (they may split a frame across
 * calls); a bad crc drops just the two sync bytes and rescans, same recovery shape as
 * tools/dp_receiver.py's Framer (the proven-on-hardware host reference).
 */
class Framer {
    private var buf = ByteArray(0)

    /** Frames dropped for a bad crc8, cumulative. */
    var resyncCount: Int = 0
        private set

    fun feed(bytes: ByteArray): List<Frame> {
        buf += bytes
        val out = mutableListOf<Frame>()
        while (true) {
            val syncIdx = findSync(buf)
            if (syncIdx < 0) {
                // No full sync in the buffer. Keep a trailing lone 0xD7 in case the second
                // sync byte arrives in the next feed() (a sync split exactly at the USB read
                // boundary) -- tools/dp_receiver.py's Framer drops it, this is stricter.
                buf = if (buf.isNotEmpty() && (buf[buf.size - 1].toInt() and 0xFF) == DP_SYNC0) {
                    byteArrayOf(buf[buf.size - 1])
                } else {
                    ByteArray(0)
                }
                break
            }
            if (syncIdx > 0) buf = buf.copyOfRange(syncIdx, buf.size)
            if (buf.size < 5) break // need type + length, wait for more bytes
            val type = buf[2].toInt() and 0xFF
            val len = (buf[3].toInt() and 0xFF) or ((buf[4].toInt() and 0xFF) shl 8)
            val frameLen = 6 + len
            if (buf.size < frameLen) break // frame not fully arrived yet
            val computed = dpCrc8(buf, 2, 5 + len)
            val received = buf[5 + len].toInt() and 0xFF
            if (computed == received) {
                out.add(Frame(type, buf.copyOfRange(5, 5 + len)))
                buf = buf.copyOfRange(frameLen, buf.size)
            } else {
                resyncCount++
                buf = buf.copyOfRange(2, buf.size) // drop only the sync pair, rescan
            }
        }
        return out
    }

    private fun findSync(b: ByteArray): Int {
        for (i in 0..b.size - 2) {
            if ((b[i].toInt() and 0xFF) == DP_SYNC0 && (b[i + 1].toInt() and 0xFF) == DP_SYNC1) return i
        }
        return -1
    }
}
