package com.droidputter.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
    target: String = BuildProxy.ENV,
    onTarget: (String) -> Unit = {},
) {
    val busyElsewhere = state?.inFlight == true && state.slug != slug
    val mine = state?.takeIf { it.slug == slug }
    Column {
        TargetSelector(target, onTarget, enabled = state?.inFlight != true)
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
            "The build proxy checks out $slug, rebuilds it against the Droidputter shim on GitHub Actions for " +
                "${BuildProxy.targetLabel(target)} and hands the parts back; flash them from the Droidputter builds tab.",
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
    target: String = BuildProxy.ENV,
    onTarget: (String) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    val slug = BuildProxy.repoSlug(text)
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        TargetSelector(target, onTarget, enabled = state?.inFlight != true)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Build any GitHub repo: owner/repo or URL") },
                singleLine = true,
                isError = text.isNotBlank() && slug == null,
                // Supporting text only when there is something to say: a blank field needs no hint row
                // (landscape height is scarce; the label already says what goes in).
                supportingText = if (text.isBlank()) null else ({
                    Text(if (slug == null) "not a GitHub repo" else "will build $slug as \"${BuildProxy.defaultName(slug)}\"")
                }),
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

/**
 * Which board the build is for (the north star, Felipe 2026-09-05: "a Cardputer app on Android with an ESP32
 * plugged in over OTG, not another Cardputer"). Two full-width buttons, filled = selected, like the catalog's
 * source selector (a tab strip was too hard to hit). The choice is one state for both build entry points.
 */
@Composable
fun TargetSelector(target: String, onTarget: (String) -> Unit, enabled: Boolean = true) {
    Column {
        Text("Build for", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.fillMaxWidth()) {
            for ((env, label) in listOf(BuildProxy.ENV_VIRTUAL to "bare ESP32-S3", BuildProxy.ENV to "Cardputer ADV")) {
                val m = Modifier.weight(1f).padding(end = if (env == BuildProxy.ENV_VIRTUAL) 8.dp else 0.dp)
                if (env == target) Button(onClick = {}, enabled = enabled, modifier = m) { Text(label) }
                else OutlinedButton(onClick = { onTarget(env) }, enabled = enabled, modifier = m) { Text(label) }
            }
        }
        Text(BuildProxy.targetLabel(target), style = MaterialTheme.typography.bodySmall)
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
    // One compact row: status text, and the run link as a small text button beside it (no second row).
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${state.displayName}${if (state.env != BuildProxy.ENV) " [${state.env}]" else ""}: $line",
            style = MaterialTheme.typography.bodySmall,
            color = if (state.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        state.runUrl?.let { url ->
            TextButton(onClick = { onOpenUrl(url) }) { Text(if (state.failed) "failed run" else "run") }
        }
    }
}
