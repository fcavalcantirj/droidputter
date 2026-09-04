package com.droidputter.catalog

import android.content.Context
import android.util.Log
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.LauncherHub
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The second catalog source: bmorcelli's LauncherHub feed (the M5Burner firmware list), fetched live and
 * cached in filesDir. The parsing lives in :core ([LauncherHub.parseFeed]); this class is only the
 * fetch/cache glue, on the same pattern as [CatalogRepository] and [VerdictRepository]. The feed is
 * ~3 MB, so it is refreshed at most once a day unless forced; offline the last copy stands. Nothing is
 * bundled: with no cache and no network the list is simply empty.
 */
class LauncherHubRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "launcherhub.json")

    @Volatile var entries: List<CatalogEntry> = loadInitial()
        private set

    /** When the cached feed was fetched, or null when there is none. */
    val fetchedAtMillis: Long? get() = cacheFile.takeIf { it.isFile }?.lastModified()

    private fun loadInitial(): List<CatalogEntry> =
        if (cacheFile.isFile) runCatching { LauncherHub.parseFeed(cacheFile.readText()) }.getOrDefault(emptyList()) else emptyList()

    /** Re-fetch the feed when the cache is older than [MAX_AGE_MS] (or [force]); true when the list changed. */
    suspend fun refresh(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val age = fetchedAtMillis?.let { System.currentTimeMillis() - it }
        if (!force && age != null && age < MAX_AGE_MS && entries.isNotEmpty()) return@withContext false
        runCatching {
            val conn = (URL(LauncherHub.FEED_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) return@runCatching false
                val text = conn.inputStream.use { it.readBytes().decodeToString() }
                val list = LauncherHub.parseFeed(text)
                if (list.isEmpty()) return@runCatching false   // a bad fetch must not blank a good cache
                cacheFile.writeText(text)
                entries = list
                true
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "launcherhub refresh failed: ${it.message}") }.getOrDefault(false)
    }

    companion object {
        const val MAX_AGE_MS = 24L * 3600 * 1000
        private const val TAG = "Droidputter"
    }
}
