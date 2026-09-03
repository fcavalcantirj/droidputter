package com.droidputter.core.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VerdictTest {
    private fun entry(name: String, sha: String) = CatalogEntry(
        name = name, board = "m5stack-stamps3", env = "m5cardputer", description = "", sourceRepo = "", license = "",
        parts = listOf(CatalogPart(offset = "0x10000", file = "firmware.bin", size = 1, sha256 = sha)), shimCommit = "abc1234",
    )

    private fun v(name: String, sha: String, result: String, date: String = "2026-09-03", note: String = "") =
        Verdict(name = name, env = "m5cardputer", firmwareSha256 = sha, shimCommit = "abc1234", board = "cardputer-adv", result = result, note = note, date = date)

    @Test
    fun jsonRoundTripAndTolerantParse() {
        val list = listOf(v("stellar-map", "aa", Verdict.RESULT_WORKS, note = "star map on the phone"), v("pigtail", "bb", Verdict.RESULT_BROKEN))
        val text = Verdict.toJson(list)
        assertTrue(text.contains("\"firmware_sha256\""))
        assertEquals(list, Verdict.parseList(text))
        assertEquals(emptyList<Verdict>(), Verdict.parseList("not json"))
        assertEquals(1, Verdict.parseList("""[{"name":"x","env":"m5cardputer","firmware_sha256":"cc","board":"b","result":"works","extra":1}]""").size)
    }

    @Test
    fun ownVerdictForSameFirmwareWins() {
        val e = entry("stellar-map", "aa")
        val s = VerdictMerge.summarize(e, remote = listOf(v("stellar-map", "aa", Verdict.RESULT_WORKS)), local = listOf(v("stellar-map", "aa", Verdict.RESULT_BROKEN)))
        assertEquals(VerdictSummary.Status.BROKEN, s.status)
        assertTrue(s.own); assertTrue(s.sameVersion)
        assertEquals("broken", s.label)
    }

    @Test
    fun communityMajorityAndTie() {
        val e = entry("stellar-map", "aa")
        val majority = VerdictMerge.summarize(e, listOf(v("stellar-map", "aa", Verdict.RESULT_WORKS), v("stellar-map", "aa", Verdict.RESULT_WORKS), v("stellar-map", "aa", Verdict.RESULT_BROKEN)), emptyList())
        assertEquals(VerdictSummary.Status.WORKS, majority.status); assertEquals(2, majority.worksCount); assertEquals(1, majority.brokenCount); assertFalse(majority.own)
        val tie = VerdictMerge.summarize(e, listOf(v("stellar-map", "aa", Verdict.RESULT_WORKS), v("stellar-map", "aa", Verdict.RESULT_BROKEN)), emptyList())
        assertEquals(VerdictSummary.Status.MIXED, tie.status)
        assertEquals("mixed (1 works / 1 broken)", tie.label)
    }

    @Test
    fun olderBuildIsFlaggedNotTrusted() {
        val e = entry("stellar-map", "new")
        val s = VerdictMerge.summarize(e, listOf(v("stellar-map", "old", Verdict.RESULT_WORKS, date = "2026-09-01"), v("stellar-map", "older", Verdict.RESULT_BROKEN, date = "2026-08-01")), emptyList())
        assertEquals(VerdictSummary.Status.WORKS, s.status)
        assertFalse(s.sameVersion)
        assertEquals("older build worked", s.label)
        assertEquals(VerdictSummary.Status.UNTESTED, VerdictMerge.summarize(entry("nothing", "zz"), emptyList(), emptyList()).status)
        assertEquals("untested", VerdictMerge.summarize(entry("nothing", "zz"), emptyList(), emptyList()).label)
    }

    @Test
    fun issueUrlCarriesTheVerdict() {
        val url = VerdictMerge.issueUrl("fcavalcantirj/droidputter", v("stellar-map", "abcdef0123456789", Verdict.RESULT_WORKS))
        assertTrue(url.startsWith("https://github.com/fcavalcantirj/droidputter/issues/new?labels=verdict&title="))
        assertTrue(url.contains("stellar-map"))
        assertTrue(url.contains("%22firmware_sha256%22"))
        assertFalse(url.contains(" "))
    }

    @Test
    fun firmwareShaComesFromTheFirmwarePart() {
        assertEquals("aa", entry("x", "aa").firmwareSha256)
        assertEquals("", CatalogEntry(name = "x", board = "b", env = "e", description = "", sourceRepo = "", license = "").firmwareSha256)
    }
}
