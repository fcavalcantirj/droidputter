package com.droidputter.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.assetDirName
import com.droidputter.core.catalog.catalogShareText
import com.droidputter.core.catalog.firmwarePart
import com.droidputter.core.catalog.parseCatalog
import com.droidputter.core.esptool.FlashImage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SEED_ASSET = "catalog/catalog.json"
private const val SHARE_CACHE_DIR = "catalog_share"

/**
 * :app's catalog glue. The index is the LIVE `apps/catalog.json` on the repo's main branch (fetched over
 * HTTPS by [refresh], cached in filesDir, seeded from the bundled asset copy -- same pattern as
 * [VerdictRepository]); parsing itself is :core's [parseCatalog]. No firmware ships in the APK: each
 * part's bytes come from [BinStore] (download by catalog url at flash/share time, sha256-verified cache).
 */
class CatalogRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "catalog.json")
    private val binStore = BinStore(context)

    @Volatile private var entries: List<CatalogEntry> = loadInitial()

    /** The catalog as last seen: the live copy cached by [refresh], else the bundled seed. */
    fun loadEntries(): List<CatalogEntry> = entries

    private fun loadInitial(): List<CatalogEntry> {
        if (cacheFile.isFile) {
            runCatching { parseCatalog(cacheFile.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return runCatching { context.assets.open(SEED_ASSET).use { parseCatalog(it.readBytes().decodeToString()) } }
            .onFailure { Log.w(TAG, "catalog seed unreadable: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Fetch the live index; keeps the cache on any failure (offline, HTTP error, unparseable). True when it changed hands. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000 }
            try {
                if (conn.responseCode != 200) return@runCatching false
                val text = conn.inputStream.use { it.readBytes().decodeToString() }
                val list = parseCatalog(text)
                if (list.isEmpty()) return@runCatching false   // never blank the catalog from a bad fetch
                cacheFile.writeText(text)
                entries = list
                true
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "catalog refresh failed: ${it.message}") }.getOrDefault(false)
    }

    /** True when every part can be produced: a download url in the catalog, or an already-cached copy. */
    fun isFlashable(entry: CatalogEntry): Boolean =
        entry.parts.isNotEmpty() && entry.parts.all { it.url != null || binStore.isCached(it) }

    /** Images in flash order plus the actual sha256 of the firmware part ("" when the entry has no parts). */
    class LoadedImages(val images: List<FlashImage>, val firmwareSha256: String)

    /** Fetches every part (cache or download, reporting progress) as in-memory images for the phone-side flasher. */
    suspend fun loadImages(entry: CatalogEntry, onProgress: (String, Int) -> Unit = { _, _ -> }): LoadedImages {
        val fwPart = entry.firmwarePart()
        var firmwareSha256 = ""
        val images = entry.parts.sortedBy { it.offset.removePrefix("0x").toLong(16) }.map { part ->
            val fetched = binStore.fetch(entry, part, onProgress)
            if (part == fwPart) firmwareSha256 = fetched.sha256
            FlashImage(part.file, part.offset.removePrefix("0x").toLong(16), fetched.bytes)
        }
        return LoadedImages(images, firmwareSha256)
    }

    /** Builds the share-sheet Intent: the offsets text blob (catalogShareText) plus a content:// stream per
     * bin part, copied out of the download cache (fetching first when needed) into the FileProvider's cache
     * dir so the receiving flasher app can read them. */
    suspend fun buildShareIntent(entry: CatalogEntry, onProgress: (String, Int) -> Unit = { _, _ -> }): Intent = withContext(Dispatchers.IO) {
        val destDir = File(context.cacheDir, "$SHARE_CACHE_DIR/${entry.assetDirName}").apply { mkdirs() }
        val uris = ArrayList<Uri>(entry.parts.size)
        for (part in entry.parts) {
            val dest = File(destDir, part.file)
            dest.writeBytes(binStore.fetch(entry, part, onProgress).bytes)
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, catalogShareText(entry))
            putExtra(Intent.EXTRA_SUBJECT, "${entry.name} (${entry.env}) — droidputter flash parts")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        const val REMOTE_URL = "https://raw.githubusercontent.com/${VerdictRepository.REPO}/main/apps/catalog.json"
        private const val TAG = "Droidputter"
    }
}
