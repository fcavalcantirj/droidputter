package com.droidputter.core.transport

import com.droidputter.core.protocol.Framer
import com.droidputter.core.protocol.decodeDpMessage
import com.droidputter.core.screen.ScreenModel
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixtureTransportTest {
    private val basePath: String
        get() = File(System.getProperty("droidputter.fixturesDir"), "pense-bem/boot").path

    // fixtures/README.md: full replay of boot.bin (3 HELLO + 57 RECT_RLE + 30 STATS) ends
    // with a non-black 240x135 framebuffer. FixtureTransport must reproduce the exact same
    // framebuffer as feeding boot.bin to Framer+ScreenModel directly -- it's the same bytes,
    // just delivered as timed chunks instead of one big read().
    @Test
    fun `replaying the fixture through FixtureTransport yields the same framebuffer as a direct bin replay`() =
        runBlocking {
            val directScreen = ScreenModel()
            Framer().feed(File("$basePath.bin").readBytes()).forEach { frame ->
                decodeDpMessage(frame)?.let { directScreen.apply(it) }
            }

            val transportScreen = ScreenModel()
            val transportFramer = Framer()
            val transport = FixtureTransport(basePath, speedFactor = 10_000.0)
            transport.incoming.collect { chunk ->
                transportFramer.feed(chunk).forEach { frame ->
                    decodeDpMessage(frame)?.let { transportScreen.apply(it) }
                }
            }

            assertArrayEquals(directScreen.snapshot(), transportScreen.snapshot())
            assertTrue(transportScreen.snapshot().any { it != 0 }, "expected a non-black framebuffer")
        }

    @Test
    fun `chunk byte count matches the recorded n and total bytes match the bin file`() = runBlocking {
        val binBytes = File("$basePath.bin").readBytes()
        val chunks = FixtureTransport(basePath, speedFactor = 10_000.0).incoming.toList()

        assertArrayEquals(binBytes, chunks.reduce { a, b -> a + b })
    }

    @Test
    fun `write records bytes without touching the incoming replay`() = runBlocking {
        val transport = FixtureTransport(basePath, speedFactor = 10_000.0)
        val helloAck = intArrayOf(0xd7, 0x50, 0x84, 0x04, 0x00, 0x38, 0x04, 0x60, 0x09, 0x23)
            .map { it.toByte() }.toByteArray()

        transport.write(helloAck)

        assertArrayEquals(helloAck, transport.writes.single())
    }
}
