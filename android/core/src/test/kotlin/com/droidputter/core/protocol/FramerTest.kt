package com.droidputter.core.protocol

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class FramerTest {
    // docs/PROTOCOL.md "phone -> ESP: KEY down/up for key '1' at (row=0, col=1)" -- the one
    // worked example with a real (non-placeholder) crc byte written out.
    private val keyDown = hex("d7 50 81 03 00 00 01 01 71")
    private val keyUp = hex("d7 50 81 03 00 00 01 00 76")

    // docs/PROTOCOL.md "phone -> ESP: HELLO_ACK (Poco X7 Pro screen size)" -- also a
    // fully-specified worked example (w=1080, h=2400 LE).
    private val helloAck = hex("d7 50 84 04 00 38 04 60 09 23")

    @Test
    fun `decodes the KEY down worked example byte-exact`() {
        val out = Framer().feed(keyDown)
        assertEquals(listOf(Frame(0x81, byteArrayOf(0, 1, 1))), out)
    }

    @Test
    fun `decodes the KEY up worked example byte-exact`() {
        val out = Framer().feed(keyUp)
        assertEquals(listOf(Frame(0x81, byteArrayOf(0, 1, 0))), out)
    }

    @Test
    fun `decodes the HELLO_ACK worked example byte-exact`() {
        val out = Framer().feed(helloAck)
        assertEquals(listOf(Frame(0x84, byteArrayOf(0x38, 0x04, 0x60, 0x09))), out)
    }

    @Test
    fun `frame split across two feeds mid-payload still decodes`() {
        val framer = Framer()
        val first = framer.feed(helloAck.copyOfRange(0, 7))
        assertTrue(first.isEmpty())
        val second = framer.feed(helloAck.copyOfRange(7, helloAck.size))
        assertEquals(listOf(Frame(0x84, byteArrayOf(0x38, 0x04, 0x60, 0x09))), second)
    }

    @Test
    fun `sync split exactly between the two sync bytes still decodes`() {
        val framer = Framer()
        val first = framer.feed(helloAck.copyOfRange(0, 1)) // just 0xD7
        assertTrue(first.isEmpty())
        val second = framer.feed(helloAck.copyOfRange(1, helloAck.size))
        assertEquals(listOf(Frame(0x84, byteArrayOf(0x38, 0x04, 0x60, 0x09))), second)
    }

    @Test
    fun `corrupted crc is skipped and the framer resyncs on the next valid frame`() {
        val framer = Framer()
        val corrupted = keyDown.copyOf()
        corrupted[corrupted.size - 1] = 0x00 // flip the crc byte
        val first = framer.feed(corrupted)
        assertTrue(first.isEmpty())
        assertEquals(1, framer.resyncCount)

        val second = framer.feed(keyUp)
        assertEquals(listOf(Frame(0x81, byteArrayOf(0, 1, 0))), second)
        assertEquals(1, framer.resyncCount) // unchanged, the good frame didn't cost a resync
    }

    @Test
    fun `noise before a frame is discarded without affecting the decode`() {
        val out = Framer().feed("garbage text\n".toByteArray() + keyDown)
        assertEquals(listOf(Frame(0x81, byteArrayOf(0, 1, 1))), out)
    }

    @Test
    fun `a near wire-ceiling payload decodes whole -- no artificial 4096 cap`() {
        // docs/PROTOCOL.md: length is u16, so 65,535 B is the real ceiling, not the shim's
        // native test-only 4096 B cap (dp_frame.h DP_FRAME_MAX_PAYLOAD, flagged in
        // progress.txt task 10 as smaller than the real wire ceiling). A real full 240x135
        // RECT payload is 64,800 B (S5, measured) -- build a synthetic frame of that size.
        val payload = ByteArray(64_800) { (it % 251).toByte() }
        val header = byteArrayOf(
            DP_SYNC0.toByte(), DP_SYNC1.toByte(), 0x03.toByte(),
            (payload.size and 0xFF).toByte(), ((payload.size shr 8) and 0xFF).toByte(),
        )
        val crcInput = header.copyOfRange(2, header.size) + payload
        val crc = dpCrc8(crcInput).toByte()
        val wire = header + payload + byteArrayOf(crc)

        val out = Framer().feed(wire)
        assertEquals(1, out.size)
        assertEquals(0x03, out[0].type)
        assertTrue(payload.contentEquals(out[0].payload))
    }

    @Test
    fun `replays the real S2 fixture boot bin with no crash and sane frame counts`() {
        // fixtures/pense-bem/boot.bin, per fixtures/README.md and re-confirmed by
        // `python3 tools/dp_receiver.py --decode fixtures/pense-bem/boot.bin`:
        // 3 HELLO + 57 RECT_RLE + 30 STATS, 3 framing errors (resynced). The bytes are real
        // hardware capture (Cardputer ADV, S2/S3), not synthetic -- counts are exact, not a
        // floor, so a re-recording of this fixture must update this test too.
        val dir = System.getProperty("droidputter.fixturesDir")
        val bootBin = File(dir, "pense-bem/boot.bin")
        val framer = Framer()
        val frames = framer.feed(bootBin.readBytes())

        val counts = frames.groupingBy { it.type }.eachCount()
        assertEquals(3, counts[0x01] ?: 0, "HELLO") // type 0x01
        assertEquals(57, counts[0x04] ?: 0, "RECT_RLE") // type 0x04
        assertEquals(30, counts[0x05] ?: 0, "STATS") // type 0x05
        assertEquals(3, framer.resyncCount)
    }
}
