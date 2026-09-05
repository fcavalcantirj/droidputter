package com.droidputter.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidputter.core.catalog.BuildProxy
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.LauncherHub
import com.droidputter.core.catalog.VerdictSummary

/**
 * The catalog, two tabs (Felipe, 2026-09-03: "hold data, not files"; nothing is bundled):
 * - **Droidputter builds**: first this phone's own proxy builds ([myBuilds], source "proxy"), then the
 *   `apps/catalog.json` recipes (fetched live). Apps rebuilt against the shim -- the phone is the screen,
 *   keyboard and GPS. Bins are downloaded by url at flash time. On top, "Build any GitHub repo".
 * - **LauncherHub** (the M5Burner firmware feed behind bmorcelli's Launcher catalog): prebuilt bins the phone
 *   flashes out of the box; the app then runs on the Cardputer's own screen and keys (no shim = no mirror).
 * One entry's details, "Flash from phone" (esptool ROM protocol over the same OTG port), the community
 * verdict for that exact firmware hash and -- for any entry whose source is a GitHub repo -- "Build mirror
 * version": the build proxy rebuilds it against the shim on demand (Felipe, 2026-09-04) and the ready build
 * lands in [myBuilds] ([navigateTo] then opens it so "Flash from phone" is right there).
 */
@Composable
fun CatalogScreen(
    entries: List<CatalogEntry>,
    binPartsAvailable: (CatalogEntry) -> Boolean,
    onShare: (CatalogEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onFlash: (CatalogEntry) -> Unit = {},
    flashStatus: String? = null,
    flashing: Boolean = false,
    summaryOf: (CatalogEntry) -> VerdictSummary? = { null },
    onVerdict: (CatalogEntry, Boolean) -> Unit = { _, _ -> },
    promptVerdictFor: CatalogEntry? = null,
    hubEntries: List<CatalogEntry> = emptyList(),
    hubStatus: String? = null,
    myBuilds: List<CatalogEntry> = emptyList(),
    buildState: BuildRequestState? = null,
    onBuild: (slug: String, seed: CatalogEntry?) -> Unit = { _, _ -> },
    onOpenUrl: (String) -> Unit = {},
    navigateTo: CatalogEntry? = null,
    onNavigated: () -> Unit = {},
) {
    var selected: CatalogEntry? by remember { mutableStateOf(null) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val current = selected
    // System back: detail -> list -> mirror. Without this the gesture finishes the activity
    // (and drops the link); with 11 entries the list also pushed the Back button off-screen.
    BackHandler { if (selected != null) selected = null else onClose() }
    // A build that just became ready: open its detail on the Droidputter tab, once.
    LaunchedEffect(navigateTo) {
        if (navigateTo != null) {
            tab = 0
            selected = navigateTo
            onNavigated()
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (current == null) {
            // Landscape phones have ~360 dp of height: only the tabs, the search field and Back are fixed
            // chrome; the caption, the build-any-repo row and the build status scroll WITH the list. Before
            // (2026-09-04 22:25, Felipe's screenshot) six stacked rows left no room for the list or Back.
            // Two plain buttons instead of a Material TabRow: Felipe (2026-09-05) found the tab strip "SOOOO HARD
            // to navigate ... clicks only change tab on few places". A Button's whole 52 dp box is the hit target.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceButton("Droidputter builds (${myBuilds.size + entries.size})", selected = tab == 0, modifier = Modifier.weight(1f)) { tab = 0 }
                SourceButton("LauncherHub (${hubEntries.size})", selected = tab == 1, modifier = Modifier.weight(1f)) { tab = 1 }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (tab == 0) "Search builds and recipes" else "Search LauncherHub (name, author, repo)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            val source = if (tab == 0) myBuilds + entries else hubEntries
            val shown = if (query.isBlank()) source else source.filter { it.matches(query) }
            LazyColumn(modifier = Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Text(
                        if (tab == 0) "Shim builds: the phone is the screen, keyboard and GPS. Your on-demand builds list first."
                        else (hubStatus ?: "LauncherHub feed") + ". Prebuilt, flash only: runs on the Cardputer's own screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (tab == 0) item { BuildAnyRepoRow(state = buildState, onBuild = { slug -> onBuild(slug, null) }, onOpenUrl = onOpenUrl) }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            when {
                                source.isEmpty() && tab == 0 -> "(no entries in apps/catalog.json)"
                                source.isEmpty() -> "(LauncherHub feed not loaded yet: open the Catalog once with network)"
                                else -> "(nothing matches \"$query\")"
                            },
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    itemsIndexed(shown) { _, entry ->
                        OutlinedButton(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth()) {
                            Text(rowText(entry, summaryOf(entry)))
                        }
                    }
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("Back")
            }
        } else {
            CatalogDetail(
                entry = current,
                binPartsAvailable = binPartsAvailable(current),
                onShare = { onShare(current) },
                onBack = { selected = null },
                onFlash = { onFlash(current) },
                flashStatus = flashStatus,
                flashing = flashing,
                summary = summaryOf(current),
                onVerdict = { works -> onVerdict(current, works) },
                promptVerdict = promptVerdictFor?.let { it.name == current.name && it.env == current.env } ?: false,
                buildSlug = BuildProxy.slugOf(current),
                buildState = buildState,
                onBuild = { slug -> onBuild(slug, current) },
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

/** A source selector that is a real button: filled when selected, outlined otherwise, 52 dp tall, fully tappable. */
@Composable
private fun SourceButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier.height(52.dp)) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) { Text(label) }
}

/** One list row: verdict mark, name, then what kind of build it is and its caveats. */
private fun rowText(entry: CatalogEntry, summary: VerdictSummary?): String = buildString {
    append(verdictMark(summary)).append(' ').append(entry.name)
    if (entry.mirror) append(" — ${entry.env} (${entry.board})") else append(" — ${entry.board}${feedVersion(entry)?.let { " v$it" } ?: ""}")
    if (!entry.mirror) append("  · flash only")
    if (entry.source == CatalogEntry.SOURCE_PROXY) append("  · your proxy build")
    if (licenseUndeclared(entry)) append("  · no license")
}

private fun CatalogEntry.matches(q: String): Boolean =
    name.contains(q, ignoreCase = true) || description.contains(q, ignoreCase = true) ||
        sourceRepo.contains(q, ignoreCase = true) || board.contains(q, ignoreCase = true)

/** The upstream commit recorded by BuildProxy.toCatalogEntry in sourceRef ("repo=... commit=abc"); null when unknown. */
private fun upstreamCommit(entry: CatalogEntry): String? =
    entry.sourceRef?.split(' ')?.firstOrNull { it.startsWith("commit=") }?.removePrefix("commit=")?.takeIf { it.isNotEmpty() }

/** The feed version recorded by LauncherHub.parseFeed in sourceRef ("fid=... version=1.2"); null for shim builds. */
private fun feedVersion(entry: CatalogEntry): String? =
    entry.sourceRef?.split(' ')?.firstOrNull { it.startsWith("version=") }?.removePrefix("version=")?.takeIf { it.isNotEmpty() }

@Composable
private fun CatalogDetail(
    entry: CatalogEntry,
    binPartsAvailable: Boolean,
    onShare: () -> Unit,
    onBack: () -> Unit,
    onFlash: () -> Unit = {},
    flashStatus: String? = null,
    flashing: Boolean = false,
    summary: VerdictSummary? = null,
    onVerdict: (Boolean) -> Unit = {},
    promptVerdict: Boolean = false,
    buildSlug: String? = null,
    buildState: BuildRequestState? = null,
    onBuild: (slug: String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    // Scrollable: in landscape the parts list pushes the buttons below the fold, where
    // neither the D-pad nor a swipe could reach them (2026-09-03 14:11 on the Poco).
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.titleLarge)
        Text("Board: ${entry.board}")
        if (entry.mirror) Text("Build env: ${entry.env}") else Text("Source: LauncherHub / M5Burner feed" + (feedVersion(entry)?.let { ", version $it" } ?: ""))
        if (entry.source == CatalogEntry.SOURCE_PROXY) {
            Text(
                "Built on demand by the build proxy for this phone" + (upstreamCommit(entry)?.let { " (upstream commit $it)" } ?: "") + ".",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(entry.description)
        if (entry.sourceRepo.isNotEmpty()) Text("Source: ${entry.sourceRepo}")
        Text(if (entry.license.isEmpty()) "License: not stated in the feed -- see the source link" else "License: ${entry.license}")
        if (licenseUndeclared(entry)) {
            // A public catalog of other people's builds: no declared license = no redistribution grant.
            // 162 of the 394 Launcher-catalog repos have none (ARCH context 2026-09-03), so say it plainly.
            Text(
                "⚠ No license declared by the author: this build is here for evaluation only; " +
                    "redistribution rights are not granted. Ask the author at the source link.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!entry.mirror) {
            Text(
                "Prebuilt binary: the phone flashes it and the app runs on the Cardputer's own screen and keys. " +
                    "No phone mirror, phone keyboard or phone GPS -- those need a shim rebuild (Droidputter builds tab).",
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.parts.size == 1 && entry.parts[0].offset == LauncherHub.OFFSET_APP) {
                Text(
                    "App-only image (flashed at 0x10000): keeps the board's current bootloader and partition table; " +
                        "any Droidputter or Arduino 8 MB build leaves a compatible one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (summary != null) {
            Text(
                "${verdictMark(summary)} ${summary.label}" +
                    (if (summary.own) " (your report)" else if (summary.worksCount + summary.brokenCount > 0) " (${summary.worksCount} works / ${summary.brokenCount} broken)" else "") +
                    (entry.shimCommit?.let { " · shim $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Parts", style = MaterialTheme.typography.titleMedium)
        for (part in entry.parts.sortedBy { it.offset.removePrefix("0x").toLong(16) }) {
            Text("${part.offset}  ${part.file}  " + (if (part.size > 0) "${part.size} B" else "size known after download"))
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (binPartsAvailable) {
            // The phone flashes the ESP itself (esptool ROM protocol over the same OTG port); the parts are
            // downloaded first (BinStore, sha256-verified cache), so a missing network never touches the link.
            Button(onClick = onFlash, enabled = !flashing, modifier = Modifier.fillMaxWidth()) {
                Text(if (flashing) "Flashing..." else "Flash from phone")
            }
            if (flashStatus != null) Text(flashStatus, style = MaterialTheme.typography.bodySmall)
            // Community verdict for THIS firmware hash: stored on the phone, then posted to the build proxy,
            // which files the GitHub issue itself so the repo's verdicts.json can carry it to every other
            // user. One tap, no browser, no account; the flashStatus line above reports saved / sent (#N).
            Text(
                if (promptVerdict) "Did it run? Tell everyone (one tap, no account):" else "Report for this exact build (one tap, no account):",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onVerdict(true) }, modifier = Modifier.weight(1f)) { Text("Works") }
                OutlinedButton(onClick = { onVerdict(false) }, modifier = Modifier.weight(1f).padding(start = 8.dp)) { Text("Broken") }
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("Share to flasher")
            }
        } else {
            Text(
                "No download url for this entry's parts (catalog metadata only) -- flash and share disabled." +
                    if (buildSlug != null) " Build the mirror version below to get flashable parts." else "",
            )
        }
        if (buildSlug != null) {
            // Any GitHub-sourced entry (recipe, LauncherHub prebuilt, or an earlier proxy build) can be rebuilt
            // against the shim on demand; the ready build lands on the Droidputter tab with url + sha256 parts.
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BuildRequestPanel(
                state = buildState,
                slug = buildSlug,
                isRebuild = entry.source == CatalogEntry.SOURCE_PROXY,
                onBuild = { onBuild(buildSlug) },
                onOpenUrl = onOpenUrl,
            )
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to list")
        }
    }
}

private fun verdictMark(summary: VerdictSummary?): String = when (summary?.status) {
    VerdictSummary.Status.WORKS -> if (summary.sameVersion) "✅" else "☑"
    VerdictSummary.Status.BROKEN -> if (summary.sameVersion) "❌" else "⚠"
    VerdictSummary.Status.MIXED -> "⚠"
    else -> "·"
}

/** True for catalog entries whose upstream declares no license (tools/make_catalog.py writes "none declared (...)"). */
private fun licenseUndeclared(entry: CatalogEntry): Boolean =
    entry.license.startsWith("none declared") || entry.license.startsWith("NOASSERTION")
