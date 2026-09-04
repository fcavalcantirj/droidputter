package com.droidputter.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The build proxy contract (v1). Felipe, 2026-09-04: shim builds are produced ON DEMAND -- the phone asks
 * the proxy for a GitHub repo @ ref, the proxy runs the GitHub Actions build (~2-4 min) and serves the
 * parts back, the phone flashes them. Nothing is pre-built or hosted. This file is the pure half: the
 * response shapes, the slug parsing, the mapping of a ready build onto a [CatalogEntry] (so the existing
 * BinStore / PhoneFlasher path flashes it unchanged) and the status line the UI shows while polling.
 * HTTP itself lives in :app (BuildProxyClient).
 */

/** Body of `POST /api/build`. Nulls are omitted on the wire. */
@Serializable
data class BuildRequest(val repo: String, val ref: String? = null, val name: String? = null)

/** Answer to `POST /api/build`: 202 = a run was started, 200 + `cached` = the proxy already has this build. */
@Serializable
data class BuildAccepted(
    @SerialName("request_id") val requestId: String,
    val repo: String = "",
    val ref: String? = null,
    val name: String? = null,
    @SerialName("shim_commit") val shimCommit: String? = null,
    val cached: Boolean = false,
    @SerialName("run_id") val runId: String? = null,
)

/** The `build` block of a ready status: what was compiled and how big it came out. */
@Serializable
data class BuildInfo(
    @SerialName("upstream_commit") val upstreamCommit: String? = null,
    // Numbers or "12.3%" strings, whatever the proxy settles on: kept raw, shown as text.
    val ram: JsonElement? = null,
    val flash: JsonElement? = null,
)

/** One flashable part of a ready build; the same fields as [CatalogPart], `offset` in "0x0" style. */
@Serializable
data class BuildPart(
    val file: String,
    val offset: String,
    val size: Long = 0,
    val sha256: String = "",
    val url: String? = null,
)

/** Answer to `GET /api/build/{request_id}`. */
@Serializable
data class BuildStatus(
    @SerialName("request_id") val requestId: String = "",
    val status: String = STATUS_UNKNOWN,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("run_url") val runUrl: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val conclusion: String? = null,
    val build: BuildInfo? = null,
    val parts: List<BuildPart> = emptyList(),
    /** Not in the v1 GET shape (the POST carries it); tolerated here so a later proxy can add it. */
    @SerialName("shim_commit") val shimCommit: String? = null,
    val error: String? = null,
) {
    /** A build the phone can flash: status ready AND at least one part to download. */
    val ready: Boolean get() = status == STATUS_READY && parts.isNotEmpty()

    /** Polling stops here: ready, failed, or a request the proxy has no record of. */
    val terminal: Boolean get() = status == STATUS_READY || status == STATUS_FAILED || status == STATUS_UNKNOWN

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_BUILDING = "building"
        const val STATUS_READY = "ready"
        const val STATUS_FAILED = "failed"
        const val STATUS_UNKNOWN = "unknown"
    }
}

/** Answer to `GET /api/shim`. */
@Serializable
data class ShimInfo(
    @SerialName("shim_commit") val shimCommit: String? = null,
    val repo: String? = null,
    val workflow: String? = null,
    @SerialName("builds_in_flight") val buildsInFlight: Int = 0,
)

/** The body of any 4xx/5xx answer: `{error}`, plus `retry_after_s` on a 429. */
@Serializable
data class ProxyError(val error: String? = null, @SerialName("retry_after_s") val retryAfterS: Int? = null)

object BuildProxy {
    /** Placeholder until the proxy is deployed; the only place the base url lives. */
    const val DEFAULT_BASE_URL = "https://droidputter-proxy.vercel.app"
    /** What every proxy build is: a Cardputer shim build for the StampS3, like the apps/catalog.json recipes. */
    const val ENV = "m5cardputer"
    const val BOARD = "m5stack-stamps3"
    const val POLL_INTERVAL_MS = 5_000L
    const val DESCRIPTION_SUFFIX = "(shim build via proxy)"

    // Lenient: a numeric run_id decodes into the String field; coerce: an explicit null lands on the default.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun buildUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/api/build"
    fun statusUrl(baseUrl: String, requestId: String): String = "${buildUrl(baseUrl)}/$requestId"
    fun shimUrl(baseUrl: String): String = "${baseUrl.trimEnd('/')}/api/shim"

    fun encodeRequest(request: BuildRequest): String = json.encodeToString(BuildRequest.serializer(), request)

    /** These three throw on a body that is not the documented shape: the caller turns that into "proxy answered garbage". */
    fun parseAccepted(text: String): BuildAccepted = json.decodeFromString(BuildAccepted.serializer(), text)
    fun parseStatus(text: String): BuildStatus = json.decodeFromString(BuildStatus.serializer(), text)
    fun parseShim(text: String): ShimInfo = json.decodeFromString(ShimInfo.serializer(), text)

    /** Never throws: an error body that is not JSON becomes its own (truncated) text. */
    fun parseError(text: String?): ProxyError {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return ProxyError()
        return runCatching { json.decodeFromString(ProxyError.serializer(), body) }
            .getOrElse { ProxyError(error = body.take(160)) }
    }

    private val ownerPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")
    private val namePattern = Regex("[A-Za-z0-9_.-]+")
    private val scpPattern = Regex("^git@github\\.com:(.+)$", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("^(?:(?:https?|git|ssh)://)?(?:[^/@]+@)?(?:www\\.)?github\\.com/(.+)$", RegexOption.IGNORE_CASE)

    /**
     * "owner/name" for anything that names a GitHub repo: `https://github.com/geo-tp/Ultimate-Remote.git`,
     * the same without `.git`, with `www.`, with a `/tree/main/...` tail, the scp form
     * `git@github.com:owner/name.git`, or a bare `owner/name`. Null for everything else -- another host,
     * a local path (the pense-bem recipe's `/Users/...`), a lone owner -- so a "Build" button never shows
     * for a source the proxy cannot fetch.
     */
    fun repoSlug(url: String): String? {
        val text = url.trim().substringBefore('#').substringBefore('?')
        if (text.isEmpty()) return null
        val scp = scpPattern.find(text)
        val fromUrl = urlPattern.find(text)
        val path = when {
            scp != null -> scp.groupValues[1]
            fromUrl != null -> fromUrl.groupValues[1]
            text.contains("://") || text.contains('@') || text.contains(':') -> return null
            text.startsWith("/") || text.startsWith(".") || text.startsWith("~") -> return null
            else -> text
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        // A GitHub url may carry /tree/<branch>/..., a bare slug must be exactly owner/name.
        if (scp == null && fromUrl == null && segments.size != 2) return null
        val owner = segments[0]
        val name = segments[1].removeSuffix(".git")
        if (!ownerPattern.matches(owner) || !namePattern.matches(name) || name == "." || name == "..") return null
        return "$owner/$name"
    }

    /** The catalog name for a build of [slug]: the repo name in the recipes' lowercase style ("geo-tp/Ultimate-Remote" -> "ultimate-remote"). */
    fun defaultName(slug: String): String =
        slug.substringAfter('/').removeSuffix(".git").lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "build" }

    /** The GitHub slug an entry was built from, whatever its source; null when its source is not on GitHub. */
    fun slugOf(entry: CatalogEntry): String? = repoSlug(entry.sourceRepo)

    /**
     * A ready build as a catalog entry the app already knows how to list, flash (parts carry url + sha256,
     * BinStore verifies) and judge (shimCommit + firmware hash identify the build for verdicts).
     * [shimCommit] comes from the POST answer unless the status carries one.
     */
    fun toCatalogEntry(
        status: BuildStatus,
        repoSlug: String,
        displayName: String,
        license: String,
        description: String,
        shimCommit: String? = status.shimCommit,
    ): CatalogEntry = CatalogEntry(
        name = displayName,
        board = BOARD,
        env = ENV,
        description = listOf(description.trim(), DESCRIPTION_SUFFIX).filter { it.isNotEmpty() }.joinToString(" "),
        sourceRepo = "https://github.com/$repoSlug",
        license = license,
        buildDir = null,
        parts = status.parts.map { CatalogPart(offset = it.offset, file = it.file, size = it.size, sha256 = it.sha256, url = it.url) },
        shimCommit = shimCommit,
        source = CatalogEntry.SOURCE_PROXY,
        sourceRef = "repo=$repoSlug commit=${status.build?.upstreamCommit.orEmpty()}",
        mirror = true,
    )

    /** The one-line status the UI shows while a request is polled; [elapsedMillis] since the phone asked. */
    fun statusLine(status: BuildStatus, elapsedMillis: Long): String = when {
        status.status == BuildStatus.STATUS_QUEUED -> "queued (${formatElapsed(elapsedMillis)} elapsed)"
        status.status == BuildStatus.STATUS_BUILDING -> "building… (run ${status.runId ?: "?"}, ${formatElapsed(elapsedMillis)} elapsed)"
        status.ready -> "ready"
        status.status == BuildStatus.STATUS_READY -> "failed (the build finished with no parts)"
        status.status == BuildStatus.STATUS_FAILED ->
            "failed (see run" + (status.conclusion?.takeIf { it.isNotBlank() && it != "failure" }?.let { ": $it" } ?: "") + ")" +
                (status.error?.takeIf { it.isNotBlank() }?.let { " -- $it" } ?: "")
        status.status == BuildStatus.STATUS_UNKNOWN -> "unknown request: the proxy has no record of it (build again)"
        else -> "${status.status}… (${formatElapsed(elapsedMillis)} elapsed)"
    }

    /** "1:23" for 83 s; "1:02:03" past an hour; negative values clamp to "0:00". */
    fun formatElapsed(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
