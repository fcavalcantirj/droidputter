package com.droidputter.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PanicSnifferTest {
    private val guru = "Guru Meditation Error: Core  0 panic'ed (Interrupt wdt timeout on CPU0)"
    private val backtrace = "Backtrace: 0x40377b0d:0x3fcebd50 0x4200a5f1:0x3fcebd70 0x4200b122:0x3fcebda0"
    private val banner = "ESP-ROM:esp32s3-20210327\r\nBuild:Mar 27 2021\r\nrst:0x1 (POWERON),boot:0x8 (SPI_FAST_FLASH_BOOT)\r\nSPIWP:0xee\r\n"

    @Test
    fun `panic and backtrace lines are reported, other console lines are not`() {
        val s = PanicSniffer()
        val lines = s.feed("$guru\r\n\r\nCore  0 register dump:\r\nPC      : 0x40377b0d  PS      : 0x00060034\r\n$backtrace\r\n\r\nELF file SHA256: 8d9fa6403536daea\r\nRebooting...\r\n".toByteArray())
        assertEquals(listOf(guru, "Core  0 register dump:", backtrace, "ELF file SHA256: 8d9fa6403536daea", "Rebooting..."), lines)
        assertTrue(PanicSniffer.isPanic(guru)); assertFalse(PanicSniffer.isPanic(backtrace)); assertFalse(PanicSniffer.isReset(guru))
    }

    @Test
    fun `boot banner yields the reset line only`() {
        val lines = PanicSniffer().feed(banner.toByteArray())
        assertEquals(listOf("rst:0x1 (POWERON),boot:0x8 (SPI_FAST_FLASH_BOOT)"), lines)
        assertTrue(PanicSniffer.isReset(lines[0])); assertFalse(PanicSniffer.isPanic(lines[0]))
    }

    @Test
    fun `a line split across chunks is reassembled`() {
        val s = PanicSniffer()
        val all = "$guru\r\n".toByteArray()
        val out = ArrayList<String>()
        for (i in all.indices step 7) out += s.feed(all.copyOfRange(i, minOf(i + 7, all.size)))
        assertEquals(listOf(guru), out)
    }

    @Test
    fun `binary frame bytes never produce a line, even when they contain a pattern`() {
        val s = PanicSniffer()
        val frame = byteArrayOf(0xA5.toByte(), 0x07, 0x00, 0x10, 0x00) + "Guru Meditation".toByteArray() + byteArrayOf(0x00, 0xFF.toByte(), '\n'.code.toByte())
        assertEquals(emptyList<String>(), s.feed(frame))
        // and the sniffer recovers on the next clean line
        assertEquals(listOf(guru), s.feed("$guru\n".toByteArray()))
    }

    @Test
    fun `overlong text is dropped instead of growing without bound`() {
        val s = PanicSniffer(maxLineLength = 100)   // the 73-char Guru line fits, the 450-char backtrace does not
        val long = "Backtrace: " + "0x4200a5f1:0x3fcebd70 ".repeat(20) + "\n"
        assertEquals(emptyList<String>(), s.feed(long.toByteArray()))
        assertEquals(listOf(guru), s.feed("$guru\n".toByteArray()))
    }
}
