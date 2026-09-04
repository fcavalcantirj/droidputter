package com.droidputter.catalog

import android.content.Context
import android.util.Log
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.CatalogPart
import com.droidputter.core.catalog.sha256Hex
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Download-at-flash-time store for catalog bin parts (Felipe, 2026-09-03: "no bundled bins in the APK;
 * hold data only; download at flash time; all on git like esp-atlas"). A part lives at
 * `filesDir/bins/<key>.bin`, key = the catalog's sha256 when it publishes one, else the sha256 of the
 * part's URL (LauncherHub carries no hash until the bytes are here). A cached file whose hash matches
 * is served with no network at all. Pure I/O glue: HTTP in, verified bytes out, no protocol logic.
 */
class BinStore(context: Context) {
    class FetchedPart(val bytes: ByteArray, val sha256: String)

    private val dir = File(context.filesDir, CACHE_DIR)

    /** True when [part] can be produced without network (a cache file exists; its hash is checked on read). */
    fun isCached(part: CatalogPart): Boolean = cacheFile(part)?.isFile == true

    /** Where this part is (or would be) cached; null when the catalog gives neither a sha256 nor a url. */
    fun cacheFile(part: CatalogPart): File? = cacheKey(part)?.let { File(dir, "$it.bin") }

    private fun cacheKey(part: CatalogPart): String? = when {
        part.sha256.isNotEmpty() -> part.sha256.lowercase()
        part.url != null -> sha256Hex(part.url!!.toByteArray())   // no smart cast across modules
        else -> null
    }

    /**
     * The part's bytes plus their actual sha256: from the cache when present and matching, else downloaded
     * from [CatalogPart.url] (following redirects -- GitHub release assets 302 to objects.githubusercontent.com),
     * streamed to a temp file with progress, hash-verified when the catalog knows the hash, then moved into place.
     * [onProgress] gets ("downloading firmware.bin 3.1 MB", pct) -- pct is -1 while the length is unknown.
     */
    suspend fun fetch(entry: CatalogEntry, part: CatalogPart, onProgress: (String, Int) -> Unit): FetchedPart =
        withContext(Dispatchers.IO) {
            val file = cacheFile(part)
                ?: throw IOException("${entry.name}: the catalog gives neither a url nor a sha256 for ${part.file}")
            cached(file, part)?.let { return@withContext it }
            val url = part.url ?: throw IOException("no download url and no cached copy of ${part.file} for ${entry.name}")
            download(url, part, file, onProgress)
        }

    private fun cached(file: File, part: CatalogPart): FetchedPart? {
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val actual = sha256Hex(bytes)
        if (part.sha256.isNotEmpty() && !actual.equals(part.sha256, ignoreCase = true)) {
            Log.w(TAG, "cached ${file.name} does not hash to its name; dropping it")
            file.delete()
            return null
        }
        return FetchedPart(bytes, actual)
    }

    private fun download(url: String, part: CatalogPart, dest: File, onProgress: (String, Int) -> Unit): FetchedPart {
        dir.mkdirs()
        val tmp = File(dir, "${dest.name}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                // Bins don't compress; "identity" keeps Content-Length visible for a real percentage.
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for ${part.file} at ${conn.url.host}")
                val total = conn.contentLengthLong
                var lastReported = -1
                onProgress("downloading ${part.file}" + if (total > 0) " ${humanSize(total)}" else "", 0)
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            val pct = if (total > 0) (written * 100 / total).toInt().coerceIn(0, 100) else -1
                            // Percent steps when the length is known; every 256 KB when it isn't.
                            if (pct != lastReported && (total > 0 || written / (256 * 1024) != (written - n) / (256 * 1024))) {
                                lastReported = pct
                                onProgress(
                                    if (total > 0) "downloading ${part.file} ${humanSize(total)}" else "downloading ${part.file} ${humanSize(written)} so far",
                                    pct,
                                )
                            }
                        }
                    }
                }
                if (total > 0 && written != total) throw IOException("${part.file}: got $written of $total bytes")
            } finally {
                conn.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (part.sha256.isNotEmpty() && !actual.equals(part.sha256, ignoreCase = true)) {
                throw IOException(
                    "${part.file}: sha256 mismatch after download (catalog ${part.sha256.take(12)}…, got ${actual.take(12)}…) -- " +
                        "the catalog or the published file is stale; nothing was kept",
                )
            }
            if (part.sha256.isEmpty() && part.size > 0 && written != part.size) {
                Log.w(TAG, "${part.file}: catalog size ${part.size} != downloaded $written (no hash to check against)")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("could not move ${tmp.name} into the bin cache")
            Log.d(TAG, "cached ${part.file} as ${dest.name} ($written B)")
            return FetchedPart(dest.readBytes(), actual)
        } catch (e: UnknownHostException) {
            throw IOException("no network and no cached copy of ${part.file}", e)
        } catch (e: SocketTimeoutException) {
            throw IOException("download of ${part.file} timed out after $written B; no cached copy", e)
        } finally {
            tmp.delete()
        }
    }

    private fun humanSize(bytes: Long): String =
        if (bytes >= 1L shl 20) "%.1f MB".format(bytes / 1048576.0) else "${(bytes + 1023) / 1024} KB"

    private companion object {
        const val CACHE_DIR = "bins"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "droidputter-android"
        const val TAG = "Droidputter"
    }
}
