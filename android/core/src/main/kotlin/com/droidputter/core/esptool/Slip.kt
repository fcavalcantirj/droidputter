package com.droidputter.core.esptool

/**
 * SLIP framing as used by the Espressif ROM bootloader (esptool "serial protocol"): frames are
 * delimited by 0xC0; inside a frame 0xC0 is escaped as 0xDB 0xDC and 0xDB as 0xDB 0xDD.
 * Pure Kotlin, no I/O: [encode] wraps one packet, [SlipDecoder] turns an arbitrary byte stream
 * (USB chunks split anywhere) back into packets.
 */
object Slip {
    const val END: Int = 0xC0
    const val ESC: Int = 0xDB
    const val ESC_END: Int = 0xDC
    const val ESC_ESC: Int = 0xDD

    fun encode(packet: ByteArray): ByteArray {
        val out = ArrayList<Byte>(packet.size + 8)
        out += END.toByte()
        for (b in packet) {
            when (b.toInt() and 0xFF) {
                END -> { out += ESC.toByte(); out += ESC_END.toByte() }
                ESC -> { out += ESC.toByte(); out += ESC_ESC.toByte() }
                else -> out += b
            }
        }
        out += END.toByte()
        return out.toByteArray()
    }
}

/** Stateful SLIP decoder: feed bytes in any chunking, collect complete packets. */
class SlipDecoder {
    private val current = ArrayList<Byte>()
    private var inFrame = false
    private var escaping = false

    fun feed(bytes: ByteArray): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                v == Slip.END -> {
                    if (inFrame && current.isNotEmpty()) packets += current.toByteArray()
                    current.clear()
                    inFrame = true
                    escaping = false
                }
                !inFrame -> {} // noise before the first delimiter (boot banner, DP frames) is dropped
                escaping -> {
                    escaping = false
                    when (v) {
                        Slip.ESC_END -> current += Slip.END.toByte()
                        Slip.ESC_ESC -> current += Slip.ESC.toByte()
                        else -> { current.clear(); inFrame = false } // malformed escape: resync on the next END
                    }
                }
                v == Slip.ESC -> escaping = true
                else -> current += b
            }
        }
        return packets
    }
}
