package com.droidputter.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.assetDirName

/**
 * Lists apps/catalog.json (bundled asset, read via [CatalogRepository]), shows one entry's
 * details, and hands off flashing to whichever flasher app the user picks from the Android
 * share sheet -- no droidputter-native flashing code, per docs/FLASHING.md's "Catalog hand-off".
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
) {
    var selected: CatalogEntry? by remember { mutableStateOf(null) }
    val current = selected

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("Catalog", style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        if (current == null) {
            if (entries.isEmpty()) {
                Text("(no entries in apps/catalog.json)")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries) { entry ->
                        OutlinedButton(onClick = { selected = entry }, modifier = Modifier.fillMaxWidth()) {
                            Text("${entry.name} — ${entry.env} (${entry.board})")
                        }
                    }
                }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
            )
        }
    }
}

@Composable
private fun CatalogDetail(
    entry: CatalogEntry,
    binPartsAvailable: Boolean,
    onShare: () -> Unit,
    onBack: () -> Unit,
    onFlash: () -> Unit = {},
    flashStatus: String? = null,
    flashing: Boolean = false,
) {
    // Scrollable: in landscape the parts list pushes "Share to flasher" below the fold, where
    // neither the D-pad nor a swipe could reach it (2026-09-03 14:11 on the Poco).
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.titleLarge)
        Text("Board: ${entry.board}")
        Text("Build env: ${entry.env}")
        Text(entry.description)
        Text("Source: ${entry.sourceRepo}")
        Text("License: ${entry.license}")

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Parts (asset dir: ${entry.assetDirName})", style = MaterialTheme.typography.titleMedium)
        for (part in entry.parts.sortedBy { it.offset.removePrefix("0x").toLong(16) }) {
            Text("${part.offset}  ${part.file}  ${part.size} B")
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (binPartsAvailable) {
            // The phone flashes the ESP itself (esptool ROM protocol over the same OTG port).
            Button(onClick = onFlash, enabled = !flashing, modifier = Modifier.fillMaxWidth()) {
                Text(if (flashing) "Flashing..." else "Flash from phone")
            }
            if (flashStatus != null) Text(flashStatus, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("Share to flasher")
            }
        } else {
            Text("Bin parts not bundled on this build (built metadata only) — share disabled.")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to list")
        }
    }
}
