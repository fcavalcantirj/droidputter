package com.droidputter.core.catalog

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val syntheticJson = """
    [
      {
        "name": "widget",
        "board": "m5stack-stamps3",
        "env": "m5cardputer",
        "description": "A test entry with no build_dir (older manifest shape).",
        "source_repo": "https://example.invalid/widget.git",
        "license": "MIT",
        "parts": [
          { "offset": "0x10000", "file": "firmware.bin", "size": 42, "sha256": "abc" },
          { "offset": "0x0", "file": "bootloader.bin", "size": 7, "sha256": "def" }
        ]
      }
    ]
""".trimIndent()

class CatalogTest {
    @Test
    fun `parses a manifest with no build_dir field (older shape) as null`() {
        val entries = parseCatalog(syntheticJson)
        assertEquals(1, entries.size)
        assertNull(entries[0].buildDir)
        assertEquals("widget-m5cardputer", entries[0].assetDirName)
    }

    @Test
    fun `unknown top-level keys are ignored, not fatal`() {
        val withExtra = syntheticJson.replace("\"license\": \"MIT\",", "\"license\": \"MIT\", \"future_field\": 123,")
        val entries = parseCatalog(withExtra)
        assertEquals(1, entries.size)
        assertEquals("widget", entries[0].name)
    }

    @Test
    fun `share text lists parts in flash offset order regardless of json order`() {
        val entry = parseCatalog(syntheticJson)[0]
        val text = catalogShareText(entry)
        val bootloaderLine = text.lineSequence().indexOfFirst { it.contains("bootloader.bin") }
        val firmwareLine = text.lineSequence().indexOfFirst { it.contains("firmware.bin") }
        assertTrue(bootloaderLine in 0 until firmwareLine)
        assertTrue(text.contains("0x0"))
        assertTrue(text.contains("0x10000"))
        assertTrue(text.contains(entry.sourceRepo))
    }

    @Test
    fun `replays the real apps catalog json produced by tools_make_catalog`() {
        val appsDir = System.getProperty("droidputter.appsDir")
        val json = File(appsDir, "catalog.json").readText()
        val entries = parseCatalog(json)

        assertTrue(entries.size >= 2)
        for (entry in entries) {
            assertEquals(4, entry.parts.size)
            assertTrue(entry.buildDir != null && entry.buildDir!!.startsWith("apps/"))
            val text = catalogShareText(entry)
            assertTrue(text.contains("bootloader.bin"))
            assertTrue(text.contains("firmware.bin"))
        }

        val penseBem = entries.first { it.name == "pense-bem" && it.env == "m5cardputer" }
        assertEquals("apps/pense-bem/.pio/build/m5cardputer", penseBem.buildDir)
        assertEquals("pense-bem-m5cardputer", penseBem.assetDirName)
    }
}
