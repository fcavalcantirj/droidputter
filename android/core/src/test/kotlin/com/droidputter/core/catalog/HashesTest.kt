package com.droidputter.core.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HashesTest {
    @Test
    fun `sha256Hex matches the FIPS 180-4 known vectors`() {
        // NIST "abc" and empty-message vectors.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
    }

    @Test
    fun `sha256Hex is lower-case hex of 32 bytes`() {
        val hex = sha256Hex(ByteArray(1_000_003) { (it * 31).toByte() })
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
    }

    private fun entry(vararg parts: CatalogPart) = CatalogEntry(
        name = "x", board = "b", env = "e", description = "", sourceRepo = "", license = "MIT", parts = parts.toList(),
    )

    @Test
    fun `firmwarePart is firmware_bin for a split build regardless of json order`() {
        val fw = CatalogPart(offset = "0x10000", file = "firmware.bin", size = 3, sha256 = "fff")
        val e = entry(fw, CatalogPart(offset = "0x0", file = "bootloader.bin", size = 1, sha256 = "aaa"), CatalogPart(offset = "0x8000", file = "partitions.bin", size = 2))
        assertEquals(fw, e.firmwarePart())
        assertEquals("fff", e.firmwareSha256)
    }

    @Test
    fun `firmwarePart of a merged single-image entry is its only part, hash unknown until downloaded`() {
        val merged = CatalogPart(offset = "0x0", file = "0eaa3d1d.bin", size = 3_309_504, url = "https://example.invalid/0eaa3d1d.bin")
        val e = entry(merged)
        assertEquals(merged, e.firmwarePart())
        assertEquals("", e.firmwareSha256)
    }

    @Test
    fun `firmwarePart without firmware_bin falls back to the highest offset part`() {
        val app = CatalogPart(offset = "0x10000", file = "app.bin", size = 3)
        val e = entry(CatalogPart(offset = "0x0", file = "boot.bin", size = 1), app)
        assertEquals(app, e.firmwarePart())
    }

    @Test
    fun `firmwarePart is null when an entry has no parts`() {
        assertNull(entry().firmwarePart())
    }
}
