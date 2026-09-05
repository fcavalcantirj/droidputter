package com.droidputter.catalog

import com.droidputter.core.catalog.BuildAccepted
import com.droidputter.core.catalog.BuildProxy
import com.droidputter.core.catalog.BuildRequest
import com.droidputter.core.catalog.BuildStatus
import com.droidputter.core.catalog.ShimInfo
import com.droidputter.core.catalog.Verdict
import com.droidputter.core.catalog.VerdictReceipt
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP glue for the build proxy (contract v1, shapes and urls in :core's [BuildProxy]): ask for a build,
 * poll it, read the proxy's shim commit. Every failure surfaces as a [ProxyException] whose message is
 * meant for the status line -- offline, busy (429 + retry), rejected (4xx), broken (5xx), garbage body.
 * Timeouts are 15 s: the proxy answers from its own state, the 2-4 min build is not on this request.
 */
class BuildProxyClient(private val baseUrl: String = BuildProxy.DEFAULT_BASE_URL) {
    class ProxyException(message: String, val httpCode: Int? = null, val retryAfterS: Int? = null, cause: Throwable? = null) :
        IOException(message, cause)

    private class Reply(val code: Int, val body: String)

    /** `POST /api/build`; 202 = started, 200 = the proxy already had it (`cached`). */
    suspend fun requestBuild(slug: String, ref: String? = null, name: String? = null, env: String? = null): BuildAccepted = withContext(Dispatchers.IO) {
        val reply = exchange("POST", BuildProxy.buildUrl(baseUrl), BuildProxy.encodeRequest(BuildRequest(slug, ref, name, env)))
        when (reply.code) {
            HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_ACCEPTED -> parse(reply) { BuildProxy.parseAccepted(it) }
            else -> throw failure(reply, "build request for $slug")
        }
    }

    /** `GET /api/build/{request_id}`; a 404 is the proxy having forgotten the request (status unknown). */
    suspend fun status(requestId: String): BuildStatus = withContext(Dispatchers.IO) {
        val reply = exchange("GET", BuildProxy.statusUrl(baseUrl, requestId))
        when (reply.code) {
            HttpURLConnection.HTTP_OK -> parse(reply) { BuildProxy.parseStatus(it) }
            HttpURLConnection.HTTP_NOT_FOUND -> BuildStatus(requestId = requestId, status = BuildStatus.STATUS_UNKNOWN)
            else -> throw failure(reply, "status of $requestId")
        }
    }

    /** `GET /api/shim`. */
    suspend fun shim(): ShimInfo = withContext(Dispatchers.IO) {
        val reply = exchange("GET", BuildProxy.shimUrl(baseUrl))
        if (reply.code == HttpURLConnection.HTTP_OK) parse(reply) { BuildProxy.parseShim(it) } else throw failure(reply, "shim info")
    }

    /**
     * `POST /api/verdict` with the verdict as its body: the proxy files the GitHub issue with its own
     * identity and answers 201 with the issue it made (200 tolerated for a proxy that dedupes). Failures
     * carry a SHORT message: they land on the catalog's status line next to "kept on this phone".
     */
    suspend fun submitVerdict(v: Verdict): VerdictReceipt = withContext(Dispatchers.IO) {
        val reply = exchange("POST", BuildProxy.verdictUrl(baseUrl), Verdict.toJson(v))
        when (reply.code) {
            HttpURLConnection.HTTP_CREATED, HttpURLConnection.HTTP_OK -> parse(reply) { BuildProxy.parseReceipt(it) }
            else -> throw verdictFailure(reply)
        }
    }

    private fun verdictFailure(reply: Reply): ProxyException {
        val err = BuildProxy.parseError(reply.body)
        val detail = err.error?.takeIf { it.isNotBlank() }?.let { ": ${it.take(80)}" }.orEmpty()
        return when (reply.code) {
            429 -> {
                val retry = err.retryAfterS ?: DEFAULT_RETRY_S
                ProxyException("proxy busy, retry in $retry s", 429, retry)
            }
            HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> ProxyException("verdict too large for the proxy (HTTP 413)", reply.code)
            in 400..499 -> ProxyException("proxy rejected it (HTTP ${reply.code}$detail)", reply.code)
            else -> ProxyException("proxy failed (HTTP ${reply.code}$detail)", reply.code)
        }
    }

    private inline fun <T> parse(reply: Reply, decode: (String) -> T): T =
        try {
            decode(reply.body)
        } catch (e: Exception) {
            throw ProxyException("the build proxy answered HTTP ${reply.code} with an unexpected body: ${reply.body.take(120).ifBlank { "(empty)" }}", reply.code, cause = e)
        }

    private fun exchange(method: String, url: String, body: String? = null): Reply {
        val host = URL(url).host
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                // A fresh POST makes the proxy do several GitHub calls after a possible cold start: the Poco's
                // first build request timed out at 15 s on 2026-09-04 while the same POST took 2 s from a Mac.
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code < HttpURLConnection.HTTP_BAD_REQUEST) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
                return Reply(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: UnknownHostException) {
            throw ProxyException("offline: cannot resolve $host (no network?)", cause = e)
        } catch (e: SocketTimeoutException) {
            throw ProxyException("the build proxy at $host did not answer within ${READ_TIMEOUT_MS / 1000} s", cause = e)
        } catch (e: ConnectException) {
            throw ProxyException("cannot reach the build proxy at $host (${e.message ?: "connection refused"})", cause = e)
        } catch (e: ProxyException) {
            throw e
        } catch (e: IOException) {
            throw ProxyException("build proxy I/O error: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }
    }

    private fun failure(reply: Reply, what: String): ProxyException {
        val err = BuildProxy.parseError(reply.body)
        val detail = err.error?.takeIf { it.isNotBlank() } ?: reply.body.take(120).ifBlank { "no detail" }
        return when {
            reply.code == 429 -> {
                val retry = err.retryAfterS ?: DEFAULT_RETRY_S
                ProxyException("the build proxy is busy ($detail); try again in $retry s", 429, retry)
            }
            reply.code in 400..499 -> ProxyException("the build proxy rejected the $what (HTTP ${reply.code}): $detail", reply.code)
            else -> ProxyException("the build proxy failed on the $what (HTTP ${reply.code}): $detail -- try again in a minute", reply.code)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 45_000
        const val DEFAULT_RETRY_S = 60
        const val USER_AGENT = "droidputter-android"
    }
}
