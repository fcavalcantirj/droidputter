package com.droidputter.core.protocol

/** Sync bytes ('D','P'), see docs/PROTOCOL.md "Framing (both directions)". */
const val DP_SYNC0: Int = 0xD7
const val DP_SYNC1: Int = 0x50

/**
 * Real wire ceiling: length is a u16 field, so 65,535 B is the largest payload the
 * framing itself can express. docs/PROTOCOL.md measured full-frame RECTs at 64,800 B
 * on real hardware (S5) -- do not reuse the shim's native test-only 4096 B cap
 * (shim/lib/DroidputterShim/src/dp_frame.h's DP_FRAME_MAX_PAYLOAD) here, that cap is
 * already flagged in progress.txt (task 10) as smaller than the real wire ceiling.
 */
const val DP_MAX_PAYLOAD: Int = 0xFFFF

/** poly 0x07 over [type, length_lo, length_hi, payload...], seeded 0. Byte-exact with
 * shim/lib/DroidputterShim/src/dp_frame.cpp:dp_frame_crc8 and tools/dp_receiver.py:crc8. */
fun dpCrc8(data: ByteArray, from: Int = 0, to: Int = data.size, seed: Int = 0): Int {
    var c = seed and 0xFF
    for (i in from until to) {
        c = c xor (data[i].toInt() and 0xFF)
        repeat(8) {
            c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF
        }
    }
    return c
}

/** One decoded ESP<->phone frame per docs/PROTOCOL.md. `type` is the raw byte (e.g. 0x01 HELLO,
 * 0x84 HELLO_ACK) -- Framer is direction-agnostic, it just splits the byte stream into frames. */
class Frame(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && payload.contentEquals(other.payload)

    override fun hashCode(): Int = type * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame(type=0x${type.toString(16)}, payload=${payload.size}B)"
}
