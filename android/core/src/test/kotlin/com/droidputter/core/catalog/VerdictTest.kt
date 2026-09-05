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
    fun submissionKeyIgnoresNoteDateAndReporter() {
        val a = v("stellar-map", "aa", Verdict.RESULT_WORKS, date = "2026-09-03", note = "first tap")
        val b = v("stellar-map", "aa", Verdict.RESULT_WORKS, date = "2026-09-04", note = "auto: linked").copy(reporter = "device-0badf00d")
        assertEquals("stellar-map|m5cardputer|aa|works", a.submissionKey)
        assertEquals(a.submissionKey, b.submissionKey)
        // a different outcome, firmware or env is a different report
        assertTrue(a.submissionKey != v("stellar-map", "aa", Verdict.RESULT_BROKEN).submissionKey)
        assertTrue(a.submissionKey != v("stellar-map", "bb", Verdict.RESULT_WORKS).submissionKey)
        assertTrue(a.submissionKey != a.copy(env = "m5cardputer-adv").submissionKey)
    }

    @Test
    fun reporterIdIsDevicePlusEightHex() {
        val id = Verdict.newReporterId(kotlin.random.Random(7))
        assertTrue(Regex("device-[0-9a-f]{8}").matches(id), id)
        assertEquals(id, Verdict.newReporterId(kotlin.random.Random(7)))            // same seed, same id
        assertTrue(id != Verdict.newReporterId(kotlin.random.Random(8)))            // different phones differ
        assertTrue(Regex("device-[0-9a-f]{8}").matches(Verdict.newReporterId()))
        assertTrue(Verdict.toJson(v("x", "aa", Verdict.RESULT_WORKS).copy(reporter = id)).contains("\"reporter\": \"$id\""))
    }

    @Test
    fun sentVerdictsRoundTripAndTolerantParse() {
        val a = v("stellar-map", "aa", Verdict.RESULT_WORKS)
        val sent = mapOf(a.submissionKey to VerdictReceipt(42, "https://github.com/fcavalcantirj/droidputter/issues/42"))
        val text = SentVerdicts.toJson(sent)
        assertTrue(text.contains("\"stellar-map|m5cardputer|aa|works\""))
        assertTrue(text.contains("\"issue_number\": 42"))
        assertEquals(sent, SentVerdicts.parse(text))
        assertEquals(emptyMap<String, VerdictReceipt>(), SentVerdicts.parse(""))
        assertEquals(emptyMap<String, VerdictReceipt>(), SentVerdicts.parse("not json"))
        assertEquals(emptyMap<String, VerdictReceipt>(), SentVerdicts.parse("{}"))
        // unknown keys inside a receipt are tolerated, a missing url is not fatal
        assertEquals(mapOf("k" to VerdictReceipt(7, "")), SentVerdicts.parse("""{"k":{"issue_number":7,"future":true}}"""))
    }

    @Test
    fun firmwareShaComesFromTheFirmwarePart() {
        assertEquals("aa", entry("x", "aa").firmwareSha256)
        assertEquals("", CatalogEntry(name = "x", board = "b", env = "e", description = "", sourceRepo = "", license = "").firmwareSha256)
    }

    @Test
    fun unsentIsTheLatestOpinionPerFirmwareMinusReceiptsAndCommunityRecords() {
        val local = listOf(
            v("m5-example", "", Verdict.RESULT_WORKS, date = "2026-09-03"),              // no hash: cannot be filed
            v("stellar-map", "aa", Verdict.RESULT_WORKS, date = "2026-09-03"),           // identical record already in the repo
            v("i2c-scanner", "bb", Verdict.RESULT_WORKS, date = "2026-09-04"),
            v("i2c-scanner", "bb", Verdict.RESULT_BROKEN, date = "2026-09-04"),          // later opinion for the same firmware
            v("porkchop", "cc", Verdict.RESULT_WORKS, date = "2026-09-04"),
            v("porkchop", "cc", Verdict.RESULT_WORKS, date = "2026-09-04", note = "auto"),
            v("audiospectrum", "dd", Verdict.RESULT_WORKS, date = "2026-09-05"),         // receipt exists
        )
        val remote = listOf(v("stellar-map", "aa", Verdict.RESULT_WORKS).copy(reporter = "fcavalcantirj"))
        val sent = setOf("audiospectrum|m5cardputer|dd|works")
        val out = VerdictMerge.unsent(local, remote, sent)
        assertEquals(listOf("i2c-scanner|m5cardputer|bb|broken", "porkchop|m5cardputer|cc|works"), out.map { it.submissionKey })
        assertEquals("auto", out[1].note)
        assertEquals(emptyList<Verdict>(), VerdictMerge.unsent(emptyList(), remote, sent))
    }
}
