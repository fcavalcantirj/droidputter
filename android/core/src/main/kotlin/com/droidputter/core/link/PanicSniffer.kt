package com.droidputter.core.link

/**
 * Picks the ESP32's own console lines out of the raw USB byte stream. The stream is normally DP frames
 * (binary), but the ROM and the panic handler print plain text on the same USB-Serial/JTAG port: the boot
 * banner (`rst:0x1 (POWERON),boot:0x8 ...`), `Guru Meditation Error: Core 0 panic'ed (...)`, register dumps
 * and `Backtrace: 0x... 0x...` (decodable with xtensa-esp32s3-elf-addr2line against the build's .elf), and
 * the ROM's `waiting for download`. Without this the phone sees a reboot loop only as silence (2026-09-03:
 * Pigtail's `Interrupt wdt timeout on CPU0` was read on the Mac, never on the phone).
 *
 * Pure and chunk-safe: feed() takes the bytes as they arrive, keeps a partial line across chunks, and returns
 * the complete lines that match one of [PATTERNS]. A non-printable byte other than CR/LF means the pending
 * text was frame data, not console output, so the partial line is discarded rather than reported.
 */
class PanicSniffer(private val maxLineLength: Int = 400) {
    private val partial = StringBuilder()
    private var tainted = false

    fun feed(chunk: ByteArray): List<String> {
        val out = ArrayList<String>(0)
        for (b in chunk) {
            val c = b.toInt() and 0xFF
            when {
                c == '\n'.code || c == '\r'.code -> {
                    if (!tainted && partial.isNotEmpty()) {
                        val line = partial.toString().trim()
                        if (line.isNotEmpty() && matches(line)) out += line
                    }
                    partial.setLength(0); tainted = false
                }
                c in 0x20..0x7E || c == '\t'.code -> {
                    if (partial.length < maxLineLength) partial.append(c.toChar()) else tainted = true
                }
                else -> tainted = true   // binary frame bytes: whatever is pending is not a console line
            }
        }
        return out
    }

    companion object {
        /** Substrings of the ESP-IDF / arduino-esp32 console lines worth surfacing. */
        val PATTERNS: List<String> = listOf(
            "Guru Meditation", "panic'ed", "Backtrace:", "register dump", "abort()", "assert failed",
            "rst:0x", "ELF file SHA256", "Rebooting...", "waiting for download", "Brownout", "wdt",
        )

        fun matches(line: String): Boolean = PATTERNS.any { line.contains(it, ignoreCase = false) }

        /** True for a line that says the app crashed (as opposed to a plain boot banner). */
        fun isPanic(line: String): Boolean =
            line.contains("Guru Meditation") || line.contains("panic'ed") || line.contains("abort()") ||
                line.contains("assert failed") || line.contains("Brownout")

        /** True for the ROM boot banner's reset line (`rst:0x1 (POWERON)`, `rst:0x7 (TG0WDT_SYS_RST)`, ...). */
        fun isReset(line: String): Boolean = line.contains("rst:0x")
    }
}
