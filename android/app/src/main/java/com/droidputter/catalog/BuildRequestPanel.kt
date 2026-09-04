package com.droidputter.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidputter.core.catalog.BuildProxy
import kotlinx.coroutines.delay

/**
 * The "Build mirror version" controls (Felipe, 2026-09-04: builds happen on demand through the proxy).
 * [BuildRequestPanel] is the button + live status under one entry's detail; [BuildAnyRepoRow] is the
 * paste-a-repo field on top of the Droidputter tab. Both only render [BuildRequestState]; the polling
 * lives in [BuildFlow]. The elapsed time ticks every second between the 5 s polls.
 */
@Composable
fun BuildRequestPanel(
    state: BuildRequestState?,
    slug: String,
    isRebuild: Boolean,
    onBuild: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val busyElsewhere = state?.inFlight == true && state.slug != slug
    val mine = state?.takeIf { it.slug == slug }
    Column {
        Button(onClick = onBuild, enabled = state?.inFlight != true, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    mine?.inFlight == true -> "Building…"
                    isRebuild -> "Rebuild with the current shim (~2-4 min)"
                    else -> "Build mirror version (~2-4 min)"
                },
            )
        }
        Text(
            "The build proxy checks out $slug, rebuilds it against the Droidputter shim on GitHub Actions and " +
                "hands the parts back; flash them from the Droidputter builds tab.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (busyElsewhere) Text("Another build (${state!!.displayName}) is in flight; one at a time.", style = MaterialTheme.typography.bodySmall)
        if (mine != null) BuildStatusLine(mine, onOpenUrl)
    }
}

/** The paste field on top of the Droidputter tab: `owner/repo` or a github.com URL, plus the flow's status. */
@Composable
fun BuildAnyRepoRow(
    state: BuildRequestState?,
    onBuild: (slug: String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val slug = BuildProxy.repoSlug(text)
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Build any GitHub repo: owner/repo or URL") },
                singleLine = true,
                isError = text.isNotBlank() && slug == null,
                supportingText = {
                    Text(
                        when {
                            text.isBlank() -> "e.g. geo-tp/Ultimate-Remote -- a shim build of any open-source Cardputer app"
                            slug == null -> "not a GitHub repo"
                            else -> "will build $slug as \"${BuildProxy.defaultName(slug)}\""
                        },
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { slug?.let(onBuild) },
                enabled = slug != null && state?.inFlight != true,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(if (state?.inFlight == true) "Building…" else "Build")
            }
        }
        if (state != null) BuildStatusLine(state, onOpenUrl)
    }
}

/** One live line for a request: the status text with a ticking elapsed time, and the run link when there is one. */
@Composable
private fun BuildStatusLine(state: BuildRequestState, onOpenUrl: (String) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.requestId, state.inFlight) {
        while (state.inFlight) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val status = state.status
    val line = if (status != null && state.inFlight) BuildProxy.statusLine(status, now - state.startedAtMillis) else state.message
    Text(
        "${state.displayName} (${state.slug}): $line",
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    state.runUrl?.let { url ->
        OutlinedButton(onClick = { onOpenUrl(url) }) { Text(if (state.failed) "Open the failed run" else "Open the GitHub run") }
    }
}
