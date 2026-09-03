package com.droidputter.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.Verdict
import com.droidputter.core.catalog.VerdictMerge
import com.droidputter.core.catalog.VerdictSummary
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Community "works / broken" verdicts for catalog builds. Remote truth is `apps/verdicts.json` on the
 * repo's main branch (fetched over HTTPS, cached in filesDir, seeded from the bundled asset); this
 * device's own verdicts live beside it. Submitting = a prefilled GitHub issue in the browser, so no
 * secret ships in the APK and the repo owner folds reports into the file.
 */
class VerdictRepository(private val context: Context) {
    private val cacheFile = File(context.filesDir, "verdicts.json")
    private val localFile = File(context.filesDir, "my_verdicts.json")

    @Volatile var remote: List<Verdict> = loadInitial()
        private set
    @Volatile var local: List<Verdict> = if (localFile.isFile) Verdict.parseList(localFile.readText()) else emptyList()
        private set

    private fun loadInitial(): List<Verdict> {
        if (cacheFile.isFile) Verdict.parseList(cacheFile.readText()).takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching { context.assets.open(SEED_ASSET).use { Verdict.parseList(it.readBytes().decodeToString()) } }.getOrDefault(emptyList())
    }

    /** Fetch the live file; keeps the cache on any failure. Returns true when the remote list was refreshed. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(REMOTE_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000 }
            try {
                if (conn.responseCode != 200) return@runCatching false
                val text = conn.inputStream.use { it.readBytes().decodeToString() }
                val list = Verdict.parseList(text)
                if (list.isEmpty() && text.trim() != "[]") return@runCatching false
                cacheFile.writeText(text)
                remote = list
                true
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.w(TAG, "verdicts refresh failed: ${it.message}") }.getOrDefault(false)
    }

    fun addLocal(v: Verdict) {
        local = local + v
        localFile.writeText(Verdict.toJson(local))
    }

    fun summarize(entry: CatalogEntry): VerdictSummary = VerdictMerge.summarize(entry, remote, local)

    fun issueIntent(v: Verdict): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(VerdictMerge.issueUrl(REPO, v)))

    companion object {
        const val REPO = "fcavalcantirj/droidputter"
        const val REMOTE_URL = "https://raw.githubusercontent.com/$REPO/main/apps/verdicts.json"
        private const val SEED_ASSET = "catalog/verdicts.json"
        private const val TAG = "Droidputter"
    }
}
