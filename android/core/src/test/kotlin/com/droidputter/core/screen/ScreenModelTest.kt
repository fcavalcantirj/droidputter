package com.droidputter.core.screen

import com.droidputter.core.protocol.DpMessage
import com.droidputter.core.protocol.Framer
import com.droidputter.core.protocol.decodeDpMessage
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenModelTest {
    @Test
    fun `HELLO resizes the framebuffer and clears prior pixels`() {
        val screen = ScreenModel(4, 2)
        screen.apply(DpMessage.Fill(0, 0, 4, 2, 0x1234))
        val dirty = screen.apply(DpMessage.Hello(0, 8, 3, 1, 16, "board", "app"))
        assertNull(dirty)
        assertEquals(8, screen.width)
        assertEquals(3, screen.height)
        assertTrue(screen.snapshot().all { it == 0 })
    }

    @Test
    fun `FILL then RECT produce the expected pixels`() {
        val screen = ScreenModel(4, 2)
        screen.apply(DpMessage.Fill(0, 0, 4, 2, 0x1234))
        val rectDirty = screen.apply(
            DpMessage.Rect(1, 0, 2, 1, intArrayOf(0xaaaa.toInt(), 0xbbbb.toInt())),
        )
        assertEquals(DirtyRect(1, 0, 2, 1), rectDirty)

        val fb = screen.snapshot()
        // row 0: fill, rect, rect, fill
        assertEquals(0x1234, fb[0])
        assertEquals(0xaaaa, fb[1])
        assertEquals(0xbbbb, fb[2])
        assertEquals(0x1234, fb[3])
        // row 1: untouched by the RECT, still the FILL color
        assertEquals(0x1234, fb[4])
        assertEquals(0x1234, fb[5])
    }

    @Test
    fun `RECT fully out of bounds is clipped to nothing and counted`() {
        val screen = ScreenModel(4, 2)
        val before = screen.clippedWrites
        val dirty = screen.apply(DpMessage.Rect(10, 10, 2, 2, IntArray(4)))
        assertNull(dirty)
        assertEquals(before + 1, screen.clippedWrites)
        assertTrue(screen.snapshot().all { it == 0 })
    }

    @Test
    fun `RECT partially out of bounds is clipped to the visible region`() {
        val screen = ScreenModel(4, 2)
        val before = screen.clippedWrites
        // 3x2 rect at x=2 on a 4-wide screen: only the first 2 columns fit.
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6)
        val dirty = screen.apply(DpMessage.Rect(2, 0, 3, 2, pixels))
        assertEquals(DirtyRect(2, 0, 2, 2), dirty)
        assertEquals(before + 1, screen.clippedWrites)

        val fb = screen.snapshot()
        assertEquals(1, fb[2]) // row 0, x=2
        assertEquals(2, fb[3]) // row 0, x=3
        assertEquals(4, fb[6]) // row 1, x=2
        assertEquals(5, fb[7]) // row 1, x=3
    }

    @Test
    fun `snapshot returns an independent copy`() {
        val screen = ScreenModel(2, 2)
        screen.apply(DpMessage.Fill(0, 0, 2, 2, 0x1111))
        val snap = screen.snapshot()
        screen.apply(DpMessage.Fill(0, 0, 2, 2, 0x2222))
        assertTrue(snap.all { it == 0x1111 })
    }

    @Test
    fun `STATS carries no pixels and returns null`() {
        val screen = ScreenModel(2, 2)
        assertNull(screen.apply(DpMessage.Stats(1, 2, 3, 4)))
    }

    // fixtures/README.md: replaying fixtures/pense-bem/boot.bin ends with a non-black
    // framebuffer, top-left pixel 0x0000 (the ADICAO screen's top-left corner is black --
    // the title/menu text starts a few pixels in, recorded byte-exact against a scratch
    // replay of the real capture, see fixtures/README.md).
    @Test
    fun `replaying the S2 fixture ends with a non-black framebuffer and the recorded top-left pixel`() {
        val dir = System.getProperty("droidputter.fixturesDir")
        val bootBin = File(dir, "pense-bem/boot.bin")
        val screen = ScreenModel()
        for (frame in Framer().feed(bootBin.readBytes())) {
            val msg = decodeDpMessage(frame) ?: continue
            screen.apply(msg)
        }
        val fb = screen.snapshot()
        assertTrue(fb.any { it != 0 }, "framebuffer must not stay all-black after a real boot capture")
        assertEquals(0x0000, fb[0])
    }
}
