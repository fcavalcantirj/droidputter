package com.droidputter.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One flash-offset part of a [CatalogEntry] (bootloader/partitions/boot_app0/firmware). */
@Serializable
data class CatalogPart(
    val offset: String,
    val file: String,
    val size: Long,
    /** Hex sha256 of the part; "" when the source publishes no hash (LauncherHub) -- filled after download. */
    val sha256: String = "",
    /** Where the bytes live. The app never bundles binaries: every part is downloaded from here at flash time. */
    val url: String? = null,
)

/** One droidputter-ready build, as produced by tools/make_catalog.py into apps/catalog.json. */
@Serializable
data class CatalogEntry(
    val name: String,
    val board: String,
    val env: String,
    val description: String,
    @SerialName("source_repo") val sourceRepo: String,
    val license: String,
    @SerialName("build_dir") val buildDir: String? = null,
    val parts: List<CatalogPart> = emptyList(),
    /** Last commit that touched shim/ when the catalog was generated: part of a build's identity. */
    @SerialName("shim_commit") val shimCommit: String? = null,
    /** "droidputter" = a shim rebuild from apps/catalog.json; "launcherhub" = a prebuilt bin from the M5Burner feed;
     *  "proxy" = a shim build the build proxy produced on demand for this phone (see BuildProxy). */
    val source: String = SOURCE_DROIDPUTTER,
    /** Upstream commit / version the build came from, for provenance. */
    @SerialName("source_ref") val sourceRef: String? = null,
    /** True when the build carries the shim (phone = screen + keys + GPS); false = flash only, runs on the board's own screen. */
    val mirror: Boolean = true,
) {
    companion object {
        const val SOURCE_DROIDPUTTER = "droidputter"
        const val SOURCE_LAUNCHERHUB = "launcherhub"
        const val SOURCE_PROXY = "proxy"
    }
}

/** Stable per-entry directory name, e.g. "pense-bem-m5cardputer" (download cache, share cache). */
val CatalogEntry.assetDirName: String
    get() = "$name-$env"

private val catalogJson = Json { ignoreUnknownKeys = true }

/** Parses apps/catalog.json (or an equivalent bundled asset copy) into [CatalogEntry] list. */
fun parseCatalog(json: String): List<CatalogEntry> = catalogJson.decodeFromString(json)

// Defaults written out: a persisted list must read back identically on a build whose defaults changed.
private val catalogJsonOut = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

/** The inverse of [parseCatalog]: how the app persists the proxy builds it owns (filesDir/my_builds.json). */
fun encodeCatalog(entries: List<CatalogEntry>): String =
    catalogJsonOut.encodeToString(ListSerializer(CatalogEntry.serializer()), entries)

/**
 * The text blob shared alongside an entry's bin files (see docs/FLASHING.md "Catalog hand-off"):
 * offsets in flash order plus enough to set up ESP32_Flasher by hand, since no droidputter-native
 * flasher exists yet.
 */
fun catalogShareText(entry: CatalogEntry): String = buildString {
    appendLine("Droidputter catalog: ${entry.name} (${entry.board}, env ${entry.env})")
    appendLine(entry.description)
    appendLine("Source: ${entry.sourceRepo} (${entry.license})")
    appendLine()
    appendLine("Flash with ESP32_Flasher: chip=ESP32S3, Bootloader Auto=ON, then add each file at its offset:")
    for (part in entry.parts.sortedBy { it.offset.removePrefix("0x").toLong(16) }) {
        appendLine("  ${part.offset}\t${part.file}\t${part.size} B\tsha256 ${part.sha256}")
    }
}

/** The firmware.bin hash: what a community verdict is tied to ("" when the entry has no firmware part). */
val CatalogEntry.firmwareSha256: String
    get() = parts.firstOrNull { it.file == "firmware.bin" }?.sha256 ?: ""
