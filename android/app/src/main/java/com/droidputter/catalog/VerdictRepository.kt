package com.droidputter.catalog

import android.content.Context
import android.util.Log
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.SentVerdicts
import com.droidputter.core.catalog.Verdict
import com.droidputter.core.catalog.VerdictMerge
import com.droidputter.core.catalog.VerdictReceipt
import com.droidputter.core.catalog.VerdictSummary
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Community "works / broken" verdicts for catalog builds. Remote truth is `apps/verdicts.json` on the
 * repo's main branch (fetched over HTTPS, cached in filesDir, seeded from the bundled asset); this
 * device's own verdicts live beside it. Submitting = one POST to the build proxy, which files the GitHub
 * issue with its own identity (Felipe, 2026-09-04: "JUST CLICK, system handles") -- no browser, no
 * account, no secret in the APK. What was sent is remembered in a side file so a report is filed once
 * per device; the reporter is an anonymous per-device id, minted here.
 */
class VerdictRepository(private val context: Context, private val proxy: BuildProxyClient = BuildProxyClient()) {
    private val cacheFile = File(context.filesDir, "verdicts.json")
    private val localFile = File(context.filesDir, "my_verdicts.json")
    // Side files: the verdict records keep the exact shape apps/verdicts.json has.
    private val sentFile = File(context.filesDir, "my_verdicts_sent.json")
    private val reporterFile = File(context.filesDir, "reporter_id")

    @Volatile var remote: List<Verdict> = loadInitial()
        private set
    @Volatile var local: List<Verdict> = if (localFile.isFile) Verdict.parseList(localFile.readText()) else emptyList()
        private set
    @Volatile private var sent: Map<String, VerdictReceipt> = if (sentFile.isFile) SentVerdicts.parse(sentFile.readText()) else emptyMap()

    /** "device-" + 8 hex chars, minted on first use and kept for the life of the install; never a person. */
    val reporter: String by lazy {
        reporterFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.startsWith(Verdict.REPORTER_PREFIX) }
            ?: Verdict.newReporterId().also { id ->
                runCatching { reporterFile.writeText(id) }.onFailure { Log.w(TAG, "reporter id not saved: ${it.message}") }
            }
    }

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

    /** The issue this device already filed for the same app / env / firmware / outcome, if any. */
    fun sentReceipt(v: Verdict): VerdictReceipt? = sent[v.submissionKey]

    /**
     * Hand the verdict to the proxy. Success = the receipt, remembered so the same report is never filed
     * twice from this device (an already-sent verdict succeeds at once, without a request). Failure = a
     * short reason for the status line, always ending in "kept on this phone": the local record stays and
     * the next tap resends.
     */
    suspend fun submit(v: Verdict): Result<VerdictReceipt> {
        sentReceipt(v)?.let { return Result.success(it) }
        if (v.firmwareSha256.isBlank()) {
            return Result.failure(IOException("no firmware hash for this build (flash it from the phone first), kept on this phone"))
        }
        return try {
            val receipt = proxy.submitVerdict(v)
            remember(v, receipt)
            Result.success(receipt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "verdict submit failed: ${e.message}")
            Result.failure(IOException(shortReason(e), e))
        }
    }

    @Synchronized
    private fun remember(v: Verdict, receipt: VerdictReceipt) {
        sent = sent + (v.submissionKey to receipt)
        runCatching { sentFile.writeText(SentVerdicts.toJson(sent)) }.onFailure { Log.w(TAG, "sent verdicts not saved: ${it.message}") }
    }

    private fun shortReason(e: Throwable): String = when {
        e is BuildProxyClient.ProxyException && e.httpCode != null -> e.message ?: "proxy error (HTTP ${e.httpCode})"
        e is SocketTimeoutException || e.cause is SocketTimeoutException -> "the proxy did not answer"
        e is BuildProxyClient.ProxyException -> "no network"
        else -> e.message ?: e.javaClass.simpleName
    } + ", kept on this phone"

    companion object {
        const val REPO = "fcavalcantirj/droidputter"
        const val REMOTE_URL = "https://raw.githubusercontent.com/$REPO/main/apps/verdicts.json"
        private const val SEED_ASSET = "catalog/verdicts.json"
        private const val TAG = "Droidputter"
    }
}
