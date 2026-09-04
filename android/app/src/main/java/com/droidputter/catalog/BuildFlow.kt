package com.droidputter.catalog

import android.util.Log
import com.droidputter.core.catalog.BuildProxy
import com.droidputter.core.catalog.BuildStatus
import com.droidputter.core.catalog.CatalogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the UI knows about the one build request in flight (or the last one finished); null = none.
 * [message] is the human line ("queued", "building… (run 1, 1:23 elapsed)", "ready", an error);
 * [status] is the last GET answer so the panel can tick the elapsed time between polls.
 */
data class BuildRequestState(
    val slug: String,
    val displayName: String,
    val startedAtMillis: Long,
    val message: String,
    val requestId: String? = null,
    val status: BuildStatus? = null,
    val runUrl: String? = null,
    /** Terminal: ready, failed, unknown, an error, or the user left the catalog. */
    val done: Boolean = false,
    val failed: Boolean = false,
    /** The catalog entry saved on ready, so the detail can jump to it. */
    val readyEntry: CatalogEntry? = null,
) {
    val inFlight: Boolean get() = !done
}

/**
 * The "Build mirror version" flow: POST the request, then poll the status every [BuildProxy.POLL_INTERVAL_MS]
 * until the proxy says ready / failed / unknown (or [MAX_POLL_MS] pass), saving a ready build into
 * [MyBuildsRepository]. One request at a time; polling stops when the catalog screen closes ([cancel]) --
 * the proxy keeps building, and asking again for the same repo comes back `cached` at once. Glue only: the
 * shapes, the slug rules and the status text are :core's [BuildProxy].
 */
class BuildFlow(
    private val scope: CoroutineScope,
    private val client: BuildProxyClient,
    private val myBuilds: MyBuildsRepository,
    private val onState: (BuildRequestState?) -> Unit,
    private val onReady: (CatalogEntry) -> Unit,
) {
    private var job: Job? = null
    private var last: BuildRequestState? = null

    val inFlight: Boolean get() = job?.isActive == true

    /** Starts a request for [slug]; ignored while another one is in flight. */
    fun start(slug: String, displayName: String, license: String, description: String, ref: String? = null) {
        if (inFlight) return
        val t0 = System.currentTimeMillis()
        publish(BuildRequestState(slug, displayName, t0, message = "asking the build proxy…"))
        job = scope.launch {
            try {
                val accepted = client.requestBuild(slug, ref, displayName)
                Log.d(TAG, "build $slug accepted: request ${accepted.requestId} cached=${accepted.cached} run=${accepted.runId}")
                update { copy(requestId = accepted.requestId, message = if (accepted.cached) "the proxy already has this build, fetching its parts" else "queued") }
                var consecutiveFailures = 0
                while (true) {
                    val status = try {
                        client.status(accepted.requestId).also { consecutiveFailures = 0 }
                    } catch (e: BuildProxyClient.ProxyException) {
                        // One lost poll must not lose the build; three in a row is the proxy being gone.
                        if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw e
                        update { copy(message = "${e.message} (retrying)") }
                        delay(BuildProxy.POLL_INTERVAL_MS)
                        continue
                    }
                    val elapsed = System.currentTimeMillis() - t0
                    if (status.ready) {
                        val entry = BuildProxy.toCatalogEntry(status, slug, displayName, license, description, shimCommit = status.shimCommit ?: accepted.shimCommit)
                        myBuilds.add(entry)
                        update { copy(status = status, runUrl = status.runUrl, done = true, readyEntry = entry, message = "Ready: flash it from the Droidputter builds tab") }
                        onReady(entry)
                        return@launch
                    }
                    update { copy(status = status, runUrl = status.runUrl, message = BuildProxy.statusLine(status, elapsed), done = status.terminal, failed = status.terminal) }
                    if (status.terminal) return@launch
                    if (elapsed > MAX_POLL_MS) {
                        update { copy(done = true, failed = true, message = "gave up after ${BuildProxy.formatElapsed(elapsed)}: still ${status.status}; build again later (a finished build comes back cached)") }
                        return@launch
                    }
                    delay(BuildProxy.POLL_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "build flow $slug failed: ${e.message}")
                update { copy(done = true, failed = true, message = e.message ?: e.toString()) }
            }
        }
    }

    /** Stop polling (leaving the catalog); the request itself keeps running on the proxy. */
    fun cancel() {
        if (!inFlight) return
        job?.cancel()
        job = null
        update { copy(done = true, message = "stopped watching; the proxy keeps building -- ask again later and a finished build comes back at once") }
    }

    private fun update(change: BuildRequestState.() -> BuildRequestState) {
        last?.let { publish(it.change()) }
    }

    private fun publish(state: BuildRequestState) {
        last = state
        onState(state)
    }

    private companion object {
        const val TAG = "Droidputter"
        const val MAX_POLL_MS = 20L * 60 * 1000   // a GitHub runner queue can stall; 20 min is beyond any healthy 2-4 min build
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
