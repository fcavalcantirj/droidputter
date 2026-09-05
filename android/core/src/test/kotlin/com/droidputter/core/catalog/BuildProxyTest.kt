package com.droidputter.core.catalog

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Bodies below follow PROXY API CONTRACT v1 (2026-09-04) verbatim; the proxy itself is not deployed yet,
// so these are the shapes the app is built against, not captures.
private const val ACCEPTED_202 = """{"request_id":"req_01","repo":"geo-tp/Ultimate-Remote","ref":"main","name":"ultimate-remote","shim_commit":"3f1c9a2","cached":false,"run_id":33919498873}"""
private const val ACCEPTED_200_CACHED = """{"request_id":"req_02","repo":"benbaker76/Pigtail","ref":null,"name":null,"shim_commit":"3f1c9a2","cached":true,"run_id":"33919498873","future":{"x":1}}"""
private const val STATUS_QUEUED = """{"request_id":"req_01","status":"queued"}"""
private const val STATUS_BUILDING = """{"request_id":"req_01","status":"building","run_id":33919498873,"run_url":"https://github.com/fcavalcantirj/droidputter/actions/runs/33919498873","started_at":"2026-09-04T21:06:16Z"}"""
private const val STATUS_READY = """{
  "request_id":"req_01","status":"ready","run_id":"33919498873",
  "run_url":"https://github.com/fcavalcantirj/droidputter/actions/runs/33919498873",
  "started_at":"2026-09-04T21:06:16Z","completed_at":"2026-09-04T21:08:20Z","conclusion":"success",
  "build":{"upstream_commit":"9c0ffee1","ram":"12.3%","flash":61.9},
  "parts":[
    {"file":"firmware.bin","offset":"0x10000","size":1234567,"sha256":"aa11","url":"https://droidputter-proxy.vercel.app/api/artifact/33919498873/firmware.bin"},
    {"file":"bootloader.bin","offset":"0x0","size":15104,"sha256":"bb22","url":"https://droidputter-proxy.vercel.app/api/artifact/33919498873/bootloader.bin"},
    {"file":"partitions.bin","offset":"0x8000","size":3072,"sha256":"cc33","url":"https://droidputter-proxy.vercel.app/api/artifact/33919498873/partitions.bin"},
    {"file":"boot_app0.bin","offset":"0xe000","size":8192,"sha256":"dd44","url":"https://droidputter-proxy.vercel.app/api/artifact/33919498873/boot_app0.bin"}
  ]
}"""
private const val STATUS_FAILED = """{"request_id":"req_01","status":"failed","run_id":"1","run_url":"https://github.com/x/y/actions/runs/1","conclusion":"failure","completed_at":"2026-09-04T21:08:20Z"}"""
private const val STATUS_UNKNOWN = """{"request_id":"nope","status":"unknown"}"""
private const val SHIM = """{"shim_commit":"3f1c9a2","repo":"fcavalcantirj/droidputter","workflow":"build-app.yml","builds_in_flight":2}"""

class BuildProxyTest {
    @Test
    fun `repoSlug accepts every way of naming a GitHub repo`() {
        val expected = "geo-tp/Ultimate-Remote"
        for (input in listOf(
            "https://github.com/geo-tp/Ultimate-Remote.git",
            "https://github.com/geo-tp/Ultimate-Remote",
            "https://github.com/geo-tp/Ultimate-Remote/",
            "http://www.github.com/geo-tp/Ultimate-Remote.git",
            "https://github.com/geo-tp/Ultimate-Remote/tree/main/src",
            "https://github.com/geo-tp/Ultimate-Remote?tab=readme#top",
            "github.com/geo-tp/Ultimate-Remote",
            "git@github.com:geo-tp/Ultimate-Remote.git",
            "ssh://git@github.com/geo-tp/Ultimate-Remote.git",
            "geo-tp/Ultimate-Remote",
            "  geo-tp/Ultimate-Remote.git \n",
        )) {
            assertEquals(expected, BuildProxy.repoSlug(input), input)
        }
        assertEquals("m5stack/M5Cardputer", BuildProxy.repoSlug("https://github.com/m5stack/M5Cardputer.git"))
        assertEquals("cyberwisk/M5Cardputer_WebRadio", BuildProxy.repoSlug("cyberwisk/M5Cardputer_WebRadio"))
    }

    @Test
    fun `repoSlug is null for local paths, other hosts and malformed slugs`() {
        for (input in listOf(
            "", "   ",
            "/Users/fcavalcanti/dev/m5/cardputter-pense-pem",   // the pense-bem recipe's source_repo
            "./apps/pense-bem", "~/dev/x",
            "https://gitlab.com/owner/name", "https://bitbucket.org/o/n.git", "gitlab.com/owner/name",
            "https://github.com/", "https://github.com/owner", "https://github.com/owner/",
            "owner", "owner/", "/name", "owner/name/extra",
            "own er/name", "owner/na me", "-owner/name", "owner/.", "owner/..",
            "git@gitlab.com:owner/name.git", "C:\\repos\\x",
        )) {
            assertNull(BuildProxy.repoSlug(input), input)
        }
    }

    @Test
    fun `defaultName is the repo name in the recipes' lowercase style`() {
        assertEquals("ultimate-remote", BuildProxy.defaultName("geo-tp/Ultimate-Remote"))
        assertEquals("m5cardputer_webradio", BuildProxy.defaultName("cyberwisk/M5Cardputer_WebRadio"))
        assertEquals("pigtail", BuildProxy.defaultName("benbaker76/Pigtail.git"))
        assertEquals("a-b", BuildProxy.defaultName("o/A  B!!"))
        assertEquals("build", BuildProxy.defaultName("o/---"))
    }

    @Test
    fun `slugOf reads a catalog entry's source repo`() {
        val hub = CatalogEntry("n", "cardputer", "launcherhub", "", sourceRepo = "https://github.com/geo-tp/Ultimate-Remote", license = "")
        assertEquals("geo-tp/Ultimate-Remote", BuildProxy.slugOf(hub))
        assertNull(BuildProxy.slugOf(hub.copy(sourceRepo = "")))
        assertNull(BuildProxy.slugOf(hub.copy(sourceRepo = "/Users/fcavalcanti/dev/m5/cardputter-pense-pem")))
    }

    @Test
    fun `urls hang off the base without doubling slashes`() {
        assertEquals("https://p.example/api/build", BuildProxy.buildUrl("https://p.example/"))
        assertEquals("https://p.example/api/build/req_01", BuildProxy.statusUrl("https://p.example", "req_01"))
        assertEquals("https://p.example/api/shim", BuildProxy.shimUrl("https://p.example"))
        assertTrue(BuildProxy.DEFAULT_BASE_URL.startsWith("https://"))
    }

    @Test
    fun `encodeRequest omits absent ref and name`() {
        assertEquals("""{"repo":"geo-tp/Ultimate-Remote"}""", BuildProxy.encodeRequest(BuildRequest("geo-tp/Ultimate-Remote")))
        assertEquals(
            """{"repo":"geo-tp/Ultimate-Remote","ref":"v1.2","name":"ultimate-remote"}""",
            BuildProxy.encodeRequest(BuildRequest("geo-tp/Ultimate-Remote", "v1.2", "ultimate-remote")),
        )
    }

    @Test
    fun `parseAccepted reads the 202 shape with a numeric run_id and the cached 200 shape with nulls and extras`() {
        val started = BuildProxy.parseAccepted(ACCEPTED_202)
        assertEquals("req_01", started.requestId)
        assertEquals("geo-tp/Ultimate-Remote", started.repo)
        assertEquals("main", started.ref)
        assertEquals("ultimate-remote", started.name)
        assertEquals("3f1c9a2", started.shimCommit)
        assertFalse(started.cached)
        assertEquals("33919498873", started.runId)

        val cached = BuildProxy.parseAccepted(ACCEPTED_200_CACHED)
        assertEquals("req_02", cached.requestId)
        assertTrue(cached.cached)
        assertNull(cached.ref)
        assertNull(cached.name)
        assertEquals("33919498873", cached.runId)

        val minimal = BuildProxy.parseAccepted("""{"request_id":"r"}""")
        assertEquals("", minimal.repo)
        assertFalse(minimal.cached)
        assertNull(minimal.runId)
    }

    @Test
    fun `parseStatus reads queued, building, ready, failed and unknown`() {
        val queued = BuildProxy.parseStatus(STATUS_QUEUED)
        assertEquals(BuildStatus.STATUS_QUEUED, queued.status)
        assertFalse(queued.ready); assertFalse(queued.terminal)
        assertTrue(queued.parts.isEmpty()); assertNull(queued.runId)

        val building = BuildProxy.parseStatus(STATUS_BUILDING)
        assertEquals("33919498873", building.runId)
        assertEquals("2026-09-04T21:06:16Z", building.startedAt)
        assertTrue(building.runUrl!!.endsWith("/runs/33919498873"))
        assertFalse(building.ready); assertFalse(building.terminal)

        val ready = BuildProxy.parseStatus(STATUS_READY)
        assertTrue(ready.ready); assertTrue(ready.terminal)
        assertEquals("success", ready.conclusion)
        assertEquals("9c0ffee1", ready.build!!.upstreamCommit)
        assertEquals("\"12.3%\"", ready.build!!.ram.toString())
        assertEquals("61.9", ready.build!!.flash.toString())
        assertEquals(listOf("firmware.bin", "bootloader.bin", "partitions.bin", "boot_app0.bin"), ready.parts.map { it.file })
        assertEquals(1234567L, ready.parts[0].size)
        assertEquals("0x10000", ready.parts[0].offset)
        assertEquals("aa11", ready.parts[0].sha256)
        assertTrue(ready.parts[0].url!!.endsWith("/api/artifact/33919498873/firmware.bin"))

        val failed = BuildProxy.parseStatus(STATUS_FAILED)
        assertFalse(failed.ready); assertTrue(failed.terminal)
        assertEquals("failure", failed.conclusion)

        val unknown = BuildProxy.parseStatus(STATUS_UNKNOWN)
        assertFalse(unknown.ready); assertTrue(unknown.terminal)

        // ready with no parts is terminal but not flashable; a part without size/sha256 defaults to 0 / "".
        val empty = BuildProxy.parseStatus("""{"request_id":"r","status":"ready","parts":[]}""")
        assertFalse(empty.ready); assertTrue(empty.terminal)
        val bare = BuildProxy.parseStatus("""{"request_id":"r","status":"ready","parts":[{"file":"f.bin","offset":"0x0","url":"u"}]}""")
        assertEquals(0L, bare.parts[0].size); assertEquals("", bare.parts[0].sha256)
        // an explicit null on a list lands on the default (coerceInputValues)
        assertTrue(BuildProxy.parseStatus("""{"request_id":"r","status":"queued","parts":null}""").parts.isEmpty())
    }

    @Test
    fun `parse functions throw on garbage so the client can say so`() {
        assertThrows(SerializationException::class.java) { BuildProxy.parseStatus("<html>502</html>") }
        assertThrows(SerializationException::class.java) { BuildProxy.parseAccepted("""{"repo":"x"}""") }   // request_id missing
        assertThrows(SerializationException::class.java) { BuildProxy.parseShim("[]") }
    }

    @Test
    fun `parseError reads the 429 body and never throws`() {
        val busy = BuildProxy.parseError("""{"error":"too many builds in flight","retry_after_s":90}""")
        assertEquals("too many builds in flight", busy.error)
        assertEquals(90, busy.retryAfterS)
        assertEquals("boom", BuildProxy.parseError("""{"error":"boom"}""").error)
        assertNull(BuildProxy.parseError("""{"error":"boom"}""").retryAfterS)
        assertEquals(ProxyError(), BuildProxy.parseError(null))
        assertEquals(ProxyError(), BuildProxy.parseError("  "))
        val html = BuildProxy.parseError("<html>" + "x".repeat(500))
        assertEquals(160, html.error!!.length)
        assertTrue(html.error!!.startsWith("<html>"))
    }

    @Test
    fun `parseShim reads the shim endpoint`() {
        val shim = BuildProxy.parseShim(SHIM)
        assertEquals("3f1c9a2", shim.shimCommit)
        assertEquals("fcavalcantirj/droidputter", shim.repo)
        assertEquals("build-app.yml", shim.workflow)
        assertEquals(2, shim.buildsInFlight)
        assertEquals(0, BuildProxy.parseShim("{}").buildsInFlight)
    }

    @Test
    fun `toCatalogEntry maps a ready build onto a flashable mirror entry`() {
        val status = BuildProxy.parseStatus(STATUS_READY)
        val entry = BuildProxy.toCatalogEntry(status, "geo-tp/Ultimate-Remote", "ultimate-remote", "NOASSERTION (geo-tp/Ultimate-Remote)", "Works with both cardputer models.", shimCommit = "3f1c9a2")
        assertEquals("ultimate-remote", entry.name)
        assertEquals("m5cardputer", entry.env)
        assertEquals("m5stack-stamps3", entry.board)
        assertEquals(CatalogEntry.SOURCE_PROXY, entry.source)
        assertEquals("proxy", entry.source)
        assertTrue(entry.mirror)
        assertEquals("repo=geo-tp/Ultimate-Remote commit=9c0ffee1", entry.sourceRef)
        assertEquals("3f1c9a2", entry.shimCommit)
        assertEquals("https://github.com/geo-tp/Ultimate-Remote", entry.sourceRepo)
        assertEquals("NOASSERTION (geo-tp/Ultimate-Remote)", entry.license)
        assertEquals("Works with both cardputer models. (shim build via proxy)", entry.description)
        assertNull(entry.buildDir)
        assertEquals("ultimate-remote-m5cardputer", entry.assetDirName)
        assertEquals(4, entry.parts.size)
        assertEquals(CatalogPart("0x10000", "firmware.bin", 1234567, "aa11", "https://droidputter-proxy.vercel.app/api/artifact/33919498873/firmware.bin"), entry.parts[0])
        assertEquals("aa11", entry.firmwareSha256)
        assertEquals("firmware.bin", entry.firmwarePart()!!.file)
        // the existing share text lists the parts in flash order
        val text = catalogShareText(entry)
        assertTrue(text.indexOf("bootloader.bin") < text.indexOf("firmware.bin"))
    }

    @Test
    fun `toCatalogEntry copes with a blank description, no upstream commit and a status-carried shim commit`() {
        val status = BuildProxy.parseStatus("""{"request_id":"r","status":"ready","shim_commit":"fromget","parts":[{"file":"firmware.bin","offset":"0x10000","size":1,"sha256":"aa","url":"u"}]}""")
        val entry = BuildProxy.toCatalogEntry(status, "o/n", "n", "", "   ")
        assertEquals("(shim build via proxy)", entry.description)
        assertEquals("repo=o/n commit=", entry.sourceRef)
        assertEquals("fromget", entry.shimCommit)
        assertEquals("", entry.license)
        assertNull(BuildProxy.toCatalogEntry(status, "o/n", "n", "", "", shimCommit = null).shimCommit)
    }

    @Test
    fun `statusLine follows the request through its states`() {
        assertEquals("queued (0:05 elapsed)", BuildProxy.statusLine(BuildProxy.parseStatus(STATUS_QUEUED), 5_000))
        assertEquals("building… (run 33919498873, 1:23 elapsed)", BuildProxy.statusLine(BuildProxy.parseStatus(STATUS_BUILDING), 83_000))
        assertEquals("building… (run ?, 0:00 elapsed)", BuildProxy.statusLine(BuildStatus("r", BuildStatus.STATUS_BUILDING), -5))
        assertEquals("ready", BuildProxy.statusLine(BuildProxy.parseStatus(STATUS_READY), 200_000))
        assertEquals("failed (the build finished with no parts)", BuildProxy.statusLine(BuildStatus("r", BuildStatus.STATUS_READY), 1))
        assertEquals("failed (see run)", BuildProxy.statusLine(BuildProxy.parseStatus(STATUS_FAILED), 1))
        assertEquals("failed (see run: cancelled) -- runner lost", BuildProxy.statusLine(BuildStatus("r", BuildStatus.STATUS_FAILED, conclusion = "cancelled", error = "runner lost"), 1))
        assertEquals("unknown request: the proxy has no record of it (build again)", BuildProxy.statusLine(BuildProxy.parseStatus(STATUS_UNKNOWN), 1))
        assertEquals("uploading… (0:10 elapsed)", BuildProxy.statusLine(BuildStatus("r", "uploading"), 10_000))
    }

    @Test
    fun `verdictUrl and parseReceipt follow the POST api-verdict contract`() {
        assertEquals("https://droidputter-proxy.vercel.app/api/verdict", BuildProxy.verdictUrl(BuildProxy.DEFAULT_BASE_URL))
        assertEquals("http://192.168.0.150:8787/api/verdict", BuildProxy.verdictUrl("http://192.168.0.150:8787/"))
        val receipt = BuildProxy.parseReceipt("""{"issue_number":42,"issue_url":"https://github.com/fcavalcantirj/droidputter/issues/42","future":1}""")
        assertEquals(VerdictReceipt(42, "https://github.com/fcavalcantirj/droidputter/issues/42"), receipt)
        assertEquals(42L, receipt.issueNumber)
        // a numeric-as-string issue number still decodes (lenient), a missing url is tolerated, garbage throws
        assertEquals(7L, BuildProxy.parseReceipt("""{"issue_number":"7"}""").issueNumber)
        assertEquals("", BuildProxy.parseReceipt("""{"issue_number":7}""").issueUrl)
        assertThrows(SerializationException::class.java) { BuildProxy.parseReceipt("""{"issue_url":"x"}""") }
        assertThrows(Exception::class.java) { BuildProxy.parseReceipt("<html>502</html>") }
        // the error side of the same contract: 429 carries retry_after_s, a 413 typically has no JSON body at all
        assertEquals(ProxyError("too many verdicts", 30), BuildProxy.parseError("""{"error":"too many verdicts","retry_after_s":30}"""))
        assertEquals(ProxyError(), BuildProxy.parseError(""))
        assertEquals(ProxyError(error = "Request Entity Too Large"), BuildProxy.parseError("Request Entity Too Large"))
    }

    @Test
    fun `formatElapsed is m,ss below an hour and h,mm,ss above`() {
        assertEquals("0:00", BuildProxy.formatElapsed(0))
        assertEquals("0:00", BuildProxy.formatElapsed(-1_000))
        assertEquals("0:59", BuildProxy.formatElapsed(59_999))
        assertEquals("1:23", BuildProxy.formatElapsed(83_000))
        assertEquals("59:59", BuildProxy.formatElapsed(3_599_000))
        assertEquals("1:02:03", BuildProxy.formatElapsed(3_723_000))
    }

    @Test
    fun `encodeCatalog round-trips a proxy entry through parseCatalog with every field intact`() {
        val entry = BuildProxy.toCatalogEntry(BuildProxy.parseStatus(STATUS_READY), "geo-tp/Ultimate-Remote", "ultimate-remote", "MIT", "d", shimCommit = "3f1c9a2")
        val other = entry.copy(name = "other", sourceRef = "repo=o/n commit=1", parts = emptyList(), shimCommit = null)
        val json = encodeCatalog(listOf(entry, other))
        assertTrue(json.contains("\"source\": \"proxy\""))
        assertTrue(json.contains("\"source_ref\": \"repo=geo-tp/Ultimate-Remote commit=9c0ffee1\""))
        assertTrue(json.contains("\"shim_commit\": \"3f1c9a2\""))
        assertTrue(json.contains("\"mirror\": true"))
        assertEquals(listOf(entry, other), parseCatalog(json))
        assertEquals("[]", encodeCatalog(emptyList()).trim())
    }
}
