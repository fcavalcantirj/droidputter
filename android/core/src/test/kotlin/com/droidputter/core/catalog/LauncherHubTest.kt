package com.droidputter.core.catalog

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Provenance: fixtures/launcherhub/feed-excerpt.json = 10 entries copied verbatim (feed order) from
// https://api.launcherhub.net/giveMeTheList on 2026-09-03 (2,689 entries that day);
// fixtures/launcherhub/fid-ultimate-remote.json = curl of
// https://api.launcherhub.net/firmwares?fid=95447ed62fe38eedd4df749c111b3177 the same day.
private val fixturesDir = File(System.getProperty("droidputter.fixturesDir"))
private fun fixture(name: String): String = File(fixturesDir, "launcherhub/$name").readText()

private const val ULTIMATE = "Ultimate Remote - Cardputer & ADV"
private const val ULTIMATE_FID = "95447ed62fe38eedd4df749c111b3177"
private const val ULTIMATE_FILE = "0eaa3d1d6149ae24099d444778227adf.bin"

private fun row(
    fid: String = "f",
    name: String = "n",
    download: Long = 0,
    versions: String,
    extra: String = "",
): String = """{"fid":"$fid","name":"$name","category":"cardputer","esp":"s3","download":$download,"versions":[$versions]$extra}"""

private fun version(version: String, publishedAt: String?, file: String = "$version.bin", format: String? = "merged"): String =
    buildString {
        append("""{"version":"$version","file":"$file"""")
        if (publishedAt != null) append(""","published_at":"$publishedAt"""")
        if (format != null) append(""","install":{"format":"$format"}""")
        append("}")
    }

class LauncherHubTest {
    private val feed = fixture("feed-excerpt.json")
    private val entries = LauncherHub.parseFeed(feed)
    private fun named(name: String) = entries.first { it.name == name }

    @Test
    fun `default filters keep cardputer and stamps3 on s3 that have a flashable version`() {
        assertEquals(
            setOf(ULTIMATE, "Doom for Cardputer", "StampFly Firmware", "Bruce Firmware (BETA)", "Cardtastic", "RuView"),
            entries.map { it.name }.toSet(),
        )
        // esp "32" (stellar-map, /BeatDestroyer/), category stickc (UIFlow2.0 StickS3), no install block (hhhh):
        assertEquals(6, entries.size)
    }

    @Test
    fun `category and chip filters are parametric`() {
        val stick = LauncherHub.parseFeed(feed, categories = setOf("stickc"))
        assertEquals(listOf("UIFlow2.0 StickS3"), stick.map { it.name })
        assertEquals("stickc", stick[0].board)

        val esp32 = LauncherHub.parseFeed(feed, chips = setOf("32"))
        assertEquals(setOf("stellar-map", "/BeatDestroyer/"), esp32.map { it.name }.toSet())
        assertEquals("", esp32.first { it.name == "/BeatDestroyer/" }.sourceRepo) // github: null
    }

    @Test
    fun `merged images flash at 0x0 and app images at 0x10000, one part per entry`() {
        assertTrue(entries.all { it.parts.size == 1 })
        assertEquals(LauncherHub.OFFSET_MERGED, named(ULTIMATE).parts[0].offset)
        assertEquals("0x0", named("Doom for Cardputer").parts[0].offset)
        assertEquals("0x10000", named("RuView").parts[0].offset)
        assertEquals("0x10000", LauncherHub.parseFeed(feed, chips = setOf("32")).first { it.name == "stellar-map" }.parts[0].offset)
    }

    @Test
    fun `part url is the CDN file unless the feed already publishes an absolute url`() {
        val cdn = named(ULTIMATE).parts[0]
        assertEquals(ULTIMATE_FILE, cdn.file)
        assertEquals(LauncherHub.CDN_BASE + ULTIMATE_FILE, cdn.url)
        assertEquals("https://m5burner-cdn.m5stack.com/firmware/$ULTIMATE_FILE", cdn.url)

        val release = named("Bruce Firmware (BETA)").parts[0]
        assertEquals("Bruce-m5stack-cardputer.bin", release.file)
        assertTrue(release.url!!.startsWith("https://github.com/") && release.url!!.endsWith("/Bruce-m5stack-cardputer.bin"))
    }

    @Test
    fun `the newest published_at version wins and is recorded in sourceRef`() {
        val ultimate = named(ULTIMATE) // 1.0 2024-06-08, 1.1 2024-08-03, 1.2 2024-08-15
        assertEquals(ULTIMATE_FILE, ultimate.parts[0].file)
        assertEquals("fid=$ULTIMATE_FID version=1.2", ultimate.sourceRef)
        assertEquals(ULTIMATE_FID, LauncherHub.fidOf(ultimate))
        assertTrue(named("StampFly Firmware").sourceRef!!.endsWith(" version=v1.1.0")) // over v1.0
    }

    @Test
    fun `versions without an install block are skipped and entries left with none are dropped`() {
        val cardtastic = named("Cardtastic") // 0.2 merged, 0.3 merged, 0.4 (newest) without install
        assertEquals("22edff616f7cc403bafeac8c99beb7bd.bin", cardtastic.parts[0].file)
        assertTrue(cardtastic.sourceRef!!.endsWith(" version=0.3"))
        assertFalse(entries.any { it.name == "hhhh" }) // single version, no install
    }

    @Test
    fun `newest-first lists pick by date not position, ties and dotted dates go to the later element`() {
        val newestFirst = "[" + row(versions = version("2.0", "2026-02-01") + "," + version("1.0", "2025-01-01")) + "]"
        assertEquals("2.0.bin", LauncherHub.parseFeed(newestFirst)[0].parts[0].file)

        val tie = "[" + row(versions = version("a", "2025-01-01") + "," + version("b", "2025-01-01") + "," + version("c", null)) + "]"
        assertEquals("b.bin", LauncherHub.parseFeed(tie)[0].parts[0].file)

        val noDates = "[" + row(versions = version("x", null) + "," + version("y", null)) + "]"
        assertEquals("y.bin", LauncherHub.parseFeed(noDates)[0].parts[0].file)

        val dotted = "[" + row(versions = version("old", "2021.04.16") + "," + version("new", "2024-08-15")) + "]"
        assertEquals("new.bin", LauncherHub.parseFeed(dotted)[0].parts[0].file)
    }

    @Test
    fun `entries are sorted by downloads descending`() {
        assertEquals(
            listOf(ULTIMATE, "Doom for Cardputer", "StampFly Firmware", "Bruce Firmware (BETA)", "Cardtastic", "RuView"),
            entries.map { it.name },
        ) // 39550, 34652, 1649, 410, 307, 126 downloads
    }

    @Test
    fun `common fields mark a flash-only prebuilt with unknown size, hash and license`() {
        for (entry in entries) {
            assertEquals(LauncherHub.ENV, entry.env)
            assertEquals(CatalogEntry.SOURCE_LAUNCHERHUB, entry.source)
            assertFalse(entry.mirror)
            assertEquals("", entry.license)
            assertNull(entry.buildDir)
            assertEquals(0L, entry.parts[0].size)
            assertEquals("", entry.parts[0].sha256)
            assertTrue(entry.parts[0].url!!.endsWith(entry.parts[0].file))
        }
        assertEquals("stamps3", named("StampFly Firmware").board)
        assertEquals("https://github.com/geo-tp/Ultimate-Remote", named(ULTIMATE).sourceRepo)

        val long = named(ULTIMATE).description // 1284 chars in the feed
        assertTrue(long.startsWith("Works with both cardputer models."))
        assertTrue(long.endsWith("... (by geo_tp)"))
        assertTrue(long.length <= LauncherHub.DESCRIPTION_CAP + " (by geo_tp)".length)
        assertEquals("yuhyuhyuhyuhyuhyuhyuh (by runtz)", named("RuView").description)
    }

    @Test
    fun `description falls back to the author or to empty when the feed has neither`() {
        val onlyAuthor = "[" + row(versions = version("1", "2025-01-01"), extra = ""","description":null,"author":" me """") + "]"  // four quotes: the JSON string needs its closing quote
        assertEquals("by me", LauncherHub.parseFeed(onlyAuthor)[0].description)
        val neither = "[" + row(versions = version("1", "2025-01-01")) + "]"
        assertEquals("", LauncherHub.parseFeed(neither)[0].description)
    }

    @Test
    fun `malformed input never throws, bad rows are skipped, good rows survive`() {
        assertEquals(emptyList<CatalogEntry>(), LauncherHub.parseFeed("not json"))
        assertEquals(emptyList<CatalogEntry>(), LauncherHub.parseFeed(""))
        assertEquals(emptyList<CatalogEntry>(), LauncherHub.parseFeed("{}"))
        assertEquals(emptyList<CatalogEntry>(), LauncherHub.parseFeed("[]"))

        val mixed = """[
            1, null, "x", [], {"fid": 5},
            {"fid":"a","name":"n","category":"cardputer","esp":"s3","versions":"nope"},
            {"fid":"b","name":"  ","category":"cardputer","esp":"s3","versions":[${version("1", "2025-01-01")}]},
            {"fid":"c","name":"empty-file","category":"cardputer","esp":"s3","versions":[{"file":"","install":{"format":"merged"}}]},
            {"fid":"d","name":"weird-format","category":"cardputer","esp":"s3","versions":[{"file":"w.bin","install":{"format":"ota"}}]},
            {"fid":"e","name":"ok","category":"cardputer","esp":"s3","download":"7","github":null,
             "versions":[{"file":"x.bin","install":{"format":"merged"}}, 3, null]}
        ]"""
        val survivors = LauncherHub.parseFeed(mixed)
        assertEquals(listOf("ok"), survivors.map { it.name })
        assertEquals("fid=e version=", survivors[0].sourceRef)
        assertEquals("", survivors[0].sourceRepo)
    }

    @Test
    fun `applyFidDetail fills the part size from Fs of the matching file`() {
        val before = named(ULTIMATE)
        val after = LauncherHub.applyFidDetail(before, fixture("fid-ultimate-remote.json"))
        assertEquals(3309504L, after.parts[0].size)
        assertEquals(before.copy(parts = listOf(before.parts[0].copy(size = 3309504L))), after)
        assertTrue(catalogShareText(after).contains("0x0\t$ULTIMATE_FILE\t3309504 B"))
    }

    @Test
    fun `applyFidDetail leaves the entry alone for another fid, an unknown file or garbage`() {
        val entry = named(ULTIMATE)
        assertSame(entry, LauncherHub.applyFidDetail(entry, "garbage"))
        assertSame(entry, LauncherHub.applyFidDetail(entry, "[]"))
        assertSame(entry, LauncherHub.applyFidDetail(entry, """{"fid":"other","versions":[{"file":"$ULTIMATE_FILE","Fs":1}]}"""))
        assertSame(entry, LauncherHub.applyFidDetail(entry, """{"fid":"$ULTIMATE_FID","versions":[{"file":"nope.bin","Fs":1}]}"""))
        assertSame(entry, LauncherHub.applyFidDetail(entry, """{"fid":"$ULTIMATE_FID","versions":[{"file":"$ULTIMATE_FILE","Fs":0}]}"""))
        // A detail without fid is trusted on file name alone.
        assertEquals(42L, LauncherHub.applyFidDetail(entry, """{"versions":[{"file":"$ULTIMATE_FILE","Fs":42}]}""").parts[0].size)
    }

    @Test
    fun `fidOf is null for entries from other sources`() {
        val shimBuild = named(ULTIMATE).copy(source = CatalogEntry.SOURCE_DROIDPUTTER)
        assertNull(LauncherHub.fidOf(shimBuild))
        assertNull(LauncherHub.fidOf(named(ULTIMATE).copy(sourceRef = null)))
        assertNull(LauncherHub.fidOf(named(ULTIMATE).copy(sourceRef = "version=1.2")))
    }
}
