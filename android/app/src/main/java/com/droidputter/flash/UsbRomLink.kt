package com.droidputter.flash

import com.droidputter.core.esptool.RomLink
import com.hoho.android.usbserial.driver.UsbSerialPort

/** [RomLink] over a usb-serial-for-android port: blocking writes, timed reads, nothing else. */
class UsbRomLink(private val port: UsbSerialPort) : RomLink {
    private val buf = ByteArray(4096)

    override fun write(bytes: ByteArray) {
        if (TRACE) android.util.Log.d("Droidputter", "rom tx ${bytes.size}B op=0x%02x: %s".format(if (bytes.size > 2) bytes[2].toInt() and 0xFF else -1, hex(bytes, 24)))
        port.write(bytes, WRITE_TIMEOUT_MS)
    }

    override fun readSome(timeoutMs: Long): ByteArray {
        val n = port.read(buf, timeoutMs.toInt().coerceAtLeast(1))
        if (n > 0 && TRACE) android.util.Log.d("Droidputter", "rom rx ${n}B: ${hex(buf, minOf(n, 48))}")
        return if (n <= 0) EMPTY else buf.copyOf(n)
    }

    private fun hex(b: ByteArray, n: Int): String = (0 until minOf(n, b.size)).joinToString(" ") { "%02x".format(b[it]) }

    /**
     * esptool's UsbJtagSerialReset: the ESP32-S3 USB-Serial/JTAG peripheral maps DTR/RTS onto the
     * chip's GPIO0/EN the way a classic serial adapter does, going through (1,1) rather than (0,0)
     * so the chip lands in the ROM download mode.
     */
    fun resetIntoBootloader() {
        port.setRTS(false); port.setDTR(false)   // idle
        Thread.sleep(100)
        port.setDTR(true); port.setRTS(false)    // IO0 low
        Thread.sleep(100)
        port.setRTS(true); port.setDTR(false)    // EN low (reset), IO0 still sampled low
        port.setRTS(true)
        Thread.sleep(100)
        port.setDTR(false); port.setRTS(false)   // chip out of reset -> ROM bootloader
    }

    /** esptool's hard_reset: pulse EN with GPIO0 released, so the freshly written app boots. */
    fun hardReset() {
        port.setDTR(false)
        port.setRTS(true)
        Thread.sleep(100)
        port.setRTS(false)
    }

    private companion object {
        const val TRACE = true   // wire trace for the first hardware runs; drop once the flow is proven
        const val WRITE_TIMEOUT_MS = 1_000
        val EMPTY = ByteArray(0)
    }
}
