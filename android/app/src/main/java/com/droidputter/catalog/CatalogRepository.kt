package com.droidputter.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.assetDirName
import com.droidputter.core.esptool.FlashImage
import com.droidputter.core.catalog.catalogShareText
import com.droidputter.core.catalog.parseCatalog
import java.io.File

private const val MANIFEST_ASSET_PATH = "catalog/catalog.json"
private const val SHARE_CACHE_DIR = "catalog_share"

/**
 * :app's only catalog-specific Android glue: reads the bundled apps/catalog.json asset
 * (parsing itself lives in :core's [parseCatalog]) and, on demand, copies an entry's bin
 * parts out of assets into cache so they can be handed to another app as content:// URIs --
 * assets themselves have no stable Uri a share Intent can point another app at.
 */
class CatalogRepository(private val context: Context) {
    fun loadEntries(): List<CatalogEntry> {
        val json = context.assets.open(MANIFEST_ASSET_PATH).use { it.readBytes().decodeToString() }
        return parseCatalog(json)
    }

    /** True if this entry's bin parts were bundled at build time (its build_dir existed on the
     * machine that ran `./gradlew :app:assembleDebug`) -- false means "built metadata only",
     * e.g. a fresh clone or a board nobody has PlatformIO-built yet this session. */
    fun hasBinParts(entry: CatalogEntry): Boolean {
        val assetDir = "catalog/${entry.assetDirName}"
        return entry.parts.isNotEmpty() && entry.parts.all { part ->
            runCatching { context.assets.open("$assetDir/${part.file}").close() }.isSuccess
        }
    }

    /** Builds the share-sheet Intent: the offsets text blob (catalogShareText) plus a
     * content:// stream per bin part, copied fresh into cache so FileProvider can grant the
     * receiving flasher app read access without exposing the app's asset storage directly. */
    /** The bundled bin parts as in-memory images for the phone-side flasher, in flash order. */
    fun loadImages(entry: CatalogEntry): List<FlashImage> {
        val assetDir = "catalog/${entry.assetDirName}"
        if (!hasBinParts(entry)) return emptyList()
        return entry.parts.sortedBy { it.offset.removePrefix("0x").toLong(16) }.map { part ->
            val bytes = context.assets.open("$assetDir/${part.file}").use { it.readBytes() }
            FlashImage(part.file, part.offset.removePrefix("0x").toLong(16), bytes)
        }
    }

    fun buildShareIntent(entry: CatalogEntry): Intent {
        val assetDir = "catalog/${entry.assetDirName}"
        val destDir = File(context.cacheDir, "$SHARE_CACHE_DIR/${entry.assetDirName}").apply { mkdirs() }
        val uris = ArrayList<Uri>(entry.parts.size)
        for (part in entry.parts) {
            val dest = File(destDir, part.file)
            context.assets.open("$assetDir/${part.file}").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        }
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, catalogShareText(entry))
            putExtra(Intent.EXTRA_SUBJECT, "${entry.name} (${entry.env}) — droidputter flash parts")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
