package com.droidputter.core.protocol

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class DpMessageTest {
    // docs/PROTOCOL.md "ESP -> phone: HELLO" worked example (payload only, decoded from
    // fixtures/pense-bem/boot.bin): proto=0, w=240, h=135, rotation=1, bpp=16,
    // board="cardputer-adv", app="app".
    @Test
    fun `decodes the HELLO worked example byte-exact`() {
        val board = "cardputer-adv".toByteArray() + ByteArray(16 - "cardputer-adv".length)
        val app = "app".toByteArray() + ByteArray(32 - "app".length)
        val payload = byteArrayOf(0x00, 0xf0.toByte(), 0x00, 0x87.toByte(), 0x00, 0x01, 0x10) + board + app
        assertEquals(55, payload.size)
        assertEquals(DpMessage.Hello(0, 240, 135, 1, 16, "cardputer-adv", "app"), decodeHello(payload))
    }

    @Test
    fun `HELLO with the wrong payload size is rejected, not a crash`() {
        assertNull(decodeHello(ByteArray(54)))
    }

    // docs/PROTOCOL.md "Note on FILL's color field": color is packed low-byte-first, unlike
    // RECT/RECT_RLE's raw big-endian panel-bus pixel bytes.
    @Test
    fun `decodes FILL with color packed low-byte-first per PROTOCOL_md`() {
        val payload = hex("01 00  02 00  03 00  04 00  34 12")
        assertEquals(DpMessage.Fill(1, 2, 3, 4, 0x1234), decodeFill(payload))
    }

    @Test
    fun `FILL with the wrong payload size is rejected, not a crash`() {
        assertNull(decodeFill(ByteArray(9)))
    }

    // No RECT worked example ships in docs/PROTOCOL.md (Pense-Bem's screen always RLE-wins),
    // but the byte layout is documented: x,y,w,h u16 LE header, then big-endian RGB565 pixels.
    @Test
    fun `decodes RECT with big-endian panel-bus pixel bytes`() {
        val header = hex("00 00  00 00  02 00  02 00") // x=0 y=0 w=2 h=2
        val pixels = hex("1234 5678 9abc def0")
        val msg = decodeRect(header + pixels)
        assertEquals(DpMessage.Rect(0, 0, 2, 2, intArrayOf(0x1234, 0x5678, 0x9abc, 0xdef0)), msg)
    }

    @Test
    fun `RECT whose w times h times 2 does not match the payload size is rejected, not a crash`() {
        val header = hex("00 00  00 00  02 00  02 00") // declares 2x2 = 8 B of pixel data
        val payload = header + hex("1234") // only 2 B supplied
        assertNull(decodeRect(payload))
    }

    // docs/PROTOCOL.md "ESP -> phone: RECT_RLE" worked example (first run of the boot
    // screen, full 240x135 frame): run(255,#000000) run(255,#000000) run(215,#000000)...
    @Test
    fun `decodes the fixture's first RECT_RLE worked example`() {
        val dir = System.getProperty("droidputter.fixturesDir")
        val bootBin = File(dir, "pense-bem/boot.bin")
        val frame = Framer().feed(bootBin.readBytes()).first { it.type == 0x04 }
        val msg = decodeRectRle(frame.payload)
        assertTrue(msg != null)
        msg!!
        assertEquals(0, msg.x)
        assertEquals(0, msg.y)
        assertEquals(240, msg.w)
        assertEquals(135, msg.h)
        assertEquals(240 * 135, msg.pixels.size)
        assertEquals(0x0000, msg.pixels[0]) // run(255,#000000) is the first run
    }

    @Test
    fun `decodes RECT_RLE runs expanding to the declared pixel count`() {
        val header = hex("00 00  00 00  02 00  02 00") // w=2 h=2 -> 4 pixels
        val runs = hex("03 1234  01 5678") // 3+1 = 4, matches w*h
        val msg = decodeRectRle(header + runs)
        assertEquals(DpMessage.RectRle(0, 0, 2, 2, intArrayOf(0x1234, 0x1234, 0x1234, 0x5678)), msg)
    }

    @Test
    fun `RECT_RLE whose run counts don't sum to w times h is rejected, not a crash`() {
        val header = hex("00 00  00 00  02 00  02 00") // w=2 h=2 -> needs 4 pixels
        val runs = hex("02 0000") // only covers 2
        assertNull(decodeRectRle(header + runs))
    }

    @Test
    fun `RECT_RLE with a zero-count run is rejected, not an infinite loop`() {
        val header = hex("00 00  00 00  02 00  02 00")
        val runs = hex("00 0000  04 0000")
        assertNull(decodeRectRle(header + runs))
    }

    // docs/PROTOCOL.md "ESP -> phone: STATS" worked example, byte-exact against the S2 line
    // in progress.txt ("ESP dropped 43 frames ... heap 160,708 B free").
    @Test
    fun `decodes the STATS worked example byte-exact`() {
        val payload = hex("31 00 00 00  87 d6 03 00  2b 00 00 00  c4 73 02 00")
        assertEquals(DpMessage.Stats(49, 251_527, 43, 160_708), decodeStats(payload))
    }

    @Test
    fun `STATS with the wrong payload size is rejected, not a crash`() {
        assertNull(decodeStats(ByteArray(15)))
    }

    @Test
    fun `an unknown frame type decodes to null instead of throwing`() {
        assertNull(decodeDpMessage(Frame(0x99, byteArrayOf(1, 2, 3))))
    }

    // fixtures/README.md: boot.bin decodes to 3 HELLO + 57 RECT_RLE + 30 STATS frames.
    @Test
    fun `every frame in the S2 fixture decodes to a typed message with counts matching fixtures README`() {
        val dir = System.getProperty("droidputter.fixturesDir")
        val bootBin = File(dir, "pense-bem/boot.bin")
        val frames = Framer().feed(bootBin.readBytes())
        val decoded = frames.map { decodeDpMessage(it) }
        assertTrue(decoded.none { it == null }, "every real captured frame must decode, not error")

        val hello = decoded.count { it is DpMessage.Hello }
        val rectRle = decoded.count { it is DpMessage.RectRle }
        val stats = decoded.count { it is DpMessage.Stats }
        assertEquals(3, hello, "HELLO")
        assertEquals(57, rectRle, "RECT_RLE")
        assertEquals(30, stats, "STATS")
    }

    @Test
    fun `the fixture's first HELLO decodes to the real captured board and app strings`() {
        val dir = System.getProperty("droidputter.fixturesDir")
        val bootBin = File(dir, "pense-bem/boot.bin")
        val frame = Framer().feed(bootBin.readBytes()).first { it.type == 0x01 }
        assertEquals(DpMessage.Hello(0, 240, 135, 1, 16, "cardputer-adv", "app"), decodeHello(frame.payload))
    }
}
