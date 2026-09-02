package com.droidputter.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

class LinkFramesTest {
    @Test
    fun `encodePingIn is a zero-payload frame`() {
        assertEquals("d7 50 83 00 00 b6", hex(encodePingIn()))
    }

    // docs/PROTOCOL.md "phone -> ESP: HELLO_ACK (Poco X7 Pro screen size)" worked example.
    @Test
    fun `encodeHelloAck is byte-exact with PROTOCOL_md`() {
        val frame = encodeHelloAck(1080, 2400)
        assertEquals("d7 50 84 04 00 38 04 60 09 23", hex(frame))
    }
}
