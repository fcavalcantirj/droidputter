package com.droidputter.core.link

import com.droidputter.core.protocol.dpCrc8
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

    @Test
    fun `encodeGpsNmea carries the sentence bytes unchanged, no CRLF appended`() {
        val sentence = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val frame = encodeGpsNmea(sentence)
        val payload = frame.copyOfRange(5, frame.size - 1)
        assertEquals(sentence, String(payload, Charsets.US_ASCII))
        assertEquals(0x82, frame[2].toInt() and 0xFF)
    }

    @Test
    fun `encodeGpsNmea crc is self-consistent with dpCrc8`() {
        val frame = encodeGpsNmea("\$GPGGA,x*00")
        val crcInput = frame.copyOfRange(2, frame.size - 1)
        assertEquals(dpCrc8(crcInput), frame.last().toInt() and 0xFF)
    }
}
