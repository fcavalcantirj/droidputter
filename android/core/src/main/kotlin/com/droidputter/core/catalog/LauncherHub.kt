package com.droidputter.core.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Second catalog source: the LauncherHub feed behind bmorcelli's Launcher catalog page, i.e. the M5Burner
 * firmware list (~2,700 entries across every M5 board). Pure parsing: the app fetches [FEED_URL], hands the
 * text here and gets [CatalogEntry]s it can list and flash next to the shim builds of apps/catalog.json.
 *
 * Why these choices:
 * - Prebuilt bins never carry the shim, so every entry is `mirror = false`: flash-only, the app then runs on
 *   the board's own screen. Only the shim rebuilds put the screen on the phone.
 * - Offsets mirror what the Launcher firmware does with the same feed (bmorcelli/Launcher
 *   webUi/scripts.js:307, src/onlineLauncher.cpp:22): `merged` images flash at 0x0, `app` images at 0x10000.
 * - The list carries neither file size nor hash, so parts start with size 0 / sha256 "": the per-fid detail
 *   ([applyFidDetail], field `Fs`) fills the size later and the hash is computed after download. The app
 *   never bundles binaries: [CatalogPart.url] is where the bytes live (the M5Burner CDN, or the absolute URL
 *   a few GitHub-release entries publish in `file`).
 * - `published_at` decides the latest version, not list position: in the real feed 158 of the 521
 *   multi-version entries list newest-first and 11 are unordered.
 * - A malformed entry is skipped, never fatal: one bad row must not hide the other 2,000.
 */
object LauncherHub {
    const val FEED_URL = "https://api.launcherhub.net/giveMeTheList"
    /** Per-firmware detail, `FID_URL + fid`: adds `Fs` (file size in bytes) per version. */
    const val FID_URL = "https://api.launcherhub.net/firmwares?fid="
    const val CDN_BASE = "https://m5burner-cdn.m5stack.com/firmware/"
    const val ENV = "launcherhub"
    const val DESCRIPTION_CAP = 300
    const val OFFSET_MERGED = "0x0"
    const val OFFSET_APP = "0x10000"

    /** Feed categories that are a Cardputer (its StampS3 module ships under `stamps3` too). */
    val DEFAULT_CATEGORIES: Set<String> = setOf("cardputer", "stamps3")
    /** Feed `esp` values that are an ESP32-S3 -- the only chip the shim targets. */
    val DEFAULT_CHIPS: Set<String> = setOf("s3")

    private class Ranked(val entry: CatalogEntry, val downloads: Long)
    private class Flashable(val version: String, val publishedAt: String, val part: CatalogPart)

    /**
     * One [CatalogEntry] per feed entry in [categories] on [chips] that has at least one version with a known
     * install format, carrying only that entry's newest flashable version; sorted by download count, most
     * downloaded first. Garbage in (not JSON, not an array, broken rows) gives an empty or shorter list.
     */
    fun parseFeed(
        json: String,
        categories: Set<String> = DEFAULT_CATEGORIES,
        chips: Set<String> = DEFAULT_CHIPS,
    ): List<CatalogEntry> {
        val rows = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonArray ?: return emptyList()
        return rows
            .mapNotNull { row -> runCatching { toEntry(row, categories, chips) }.getOrNull() }
            .sortedByDescending { it.downloads }
            .map { it.entry }
    }

    private fun toEntry(row: JsonElement, categories: Set<String>, chips: Set<String>): Ranked? {
        val obj = row as? JsonObject ?: return null
        val fid = obj.str("fid")?.trim().orEmpty()
        val name = obj.str("name")?.trim().orEmpty()
        val category = obj.str("category")?.trim().orEmpty()
        val chip = obj.str("esp")?.trim().orEmpty()
        if (fid.isEmpty() || name.isEmpty() || category !in categories || chip !in chips) return null
        val latest = latestFlashable(obj["versions"] as? JsonArray ?: return null) ?: return null
        val entry = CatalogEntry(
            name = name,
            board = category,
            env = ENV,
            description = describe(obj.str("description"), obj.str("author")),
            sourceRepo = obj.str("github")?.trim().orEmpty(),
            license = "",
            parts = listOf(latest.part),
            source = CatalogEntry.SOURCE_LAUNCHERHUB,
            sourceRef = "fid=$fid version=${latest.version}",
            mirror = false,
        )
        return Ranked(entry, obj.long("download"))
    }

    /** Newest `published_at` among the flashable versions; ties (or missing dates) go to the later element. */
    private fun latestFlashable(versions: JsonArray): Flashable? =
        versions.mapNotNull { toFlashable(it) }
            .withIndex()
            .maxWithOrNull(compareBy({ it.value.publishedAt }, { it.index }))
            ?.value

    private fun toFlashable(element: JsonElement): Flashable? {
        val v = element as? JsonObject ?: return null
        val file = v.str("file")?.trim().orEmpty()
        val offset = when ((v["install"] as? JsonObject)?.str("format")) {
            "merged" -> OFFSET_MERGED
            "app" -> OFFSET_APP
            else -> return null // no install block = the Launcher cannot flash it either
        }
        if (file.isEmpty()) return null
        val url = if (file.startsWith("http://") || file.startsWith("https://")) file else CDN_BASE + file
        return Flashable(
            version = v.str("version")?.trim().orEmpty(),
            // The feed mixes "2024-08-15" and "2021.04.16"; one separator keeps the string compare honest.
            publishedAt = v.str("published_at")?.trim()?.replace('.', '-').orEmpty(),
            part = CatalogPart(offset = offset, file = file.substringAfterLast('/'), size = 0, sha256 = "", url = url),
        )
    }

    private fun describe(description: String?, author: String?): String {
        val text = description?.trim().orEmpty().let {
            if (it.length > DESCRIPTION_CAP) it.take(DESCRIPTION_CAP - 3).trimEnd() + "..." else it
        }
        val by = author?.trim().orEmpty()
        return when {
            by.isEmpty() -> text
            text.isEmpty() -> "by $by"
            else -> "$text (by $by)"
        }
    }

    /**
     * Fills [CatalogPart.size] from the per-fid detail (`FID_URL + fid`, `versions[].Fs`) for every part whose
     * file the detail lists. Returns the entry untouched when the detail is malformed or names another fid.
     */
    fun applyFidDetail(entry: CatalogEntry, detailJson: String): CatalogEntry {
        val obj = runCatching { Json.parseToJsonElement(detailJson) }.getOrNull() as? JsonObject ?: return entry
        val fid = obj.str("fid")
        if (fid != null && fid != fidOf(entry)) return entry
        val sizes = (obj["versions"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { v -> v.str("file")?.substringAfterLast('/')?.let { file -> file to v.long("Fs") } }
            .filter { (_, size) -> size > 0 }
            .toMap()
        val parts = entry.parts.map { part -> sizes[part.file]?.let { part.copy(size = it) } ?: part }
        return if (parts == entry.parts) entry else entry.copy(parts = parts)
    }

    /** The feed fid an entry came from (read back from [CatalogEntry.sourceRef]); null for other sources. */
    fun fidOf(entry: CatalogEntry): String? = entry.sourceRef
        ?.takeIf { entry.source == CatalogEntry.SOURCE_LAUNCHERHUB }
        ?.split(' ')
        ?.firstOrNull { it.startsWith("fid=") }
        ?.removePrefix("fid=")
        ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
