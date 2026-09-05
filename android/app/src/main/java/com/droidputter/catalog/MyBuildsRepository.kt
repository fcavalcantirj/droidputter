package com.droidputter.catalog

import android.content.Context
import android.util.Log
import com.droidputter.core.catalog.CatalogEntry
import com.droidputter.core.catalog.encodeCatalog
import com.droidputter.core.catalog.parseCatalog
import java.io.File

/**
 * The shim builds the proxy produced for THIS phone (source "proxy"), persisted as a plain catalog list in
 * filesDir/my_builds.json so they survive restarts and list first on the Droidputter tab. Same shape as
 * apps/catalog.json (:core's parseCatalog / encodeCatalog), so the flash path treats them like any entry:
 * parts carry url + sha256, BinStore downloads and verifies. One entry per build identity (sourceRef =
 * "repo=<slug> commit=<upstream>"): asking again for the same repo replaces the old build with the new one.
 */
class MyBuildsRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)

    @Volatile var entries: List<CatalogEntry> = load()
        private set

    private fun load(): List<CatalogEntry> =
        if (file.isFile) runCatching { parseCatalog(file.readText()) }.onFailure { Log.w(TAG, "my_builds unreadable: ${it.message}") }.getOrDefault(emptyList())
        else emptyList()

    /** Stores a ready build, newest first; an entry with the same sourceRef (same repo + upstream commit) AND env
     *  (ADV vs bare ESP32-S3 builds of one commit are two different firmwares) is replaced. */
    fun add(entry: CatalogEntry): CatalogEntry {
        entries = listOf(entry) + entries.filterNot { it.sourceRef == entry.sourceRef && it.env == entry.env }
        save()
        return entry
    }

    private fun save() {
        runCatching { file.writeText(encodeCatalog(entries)) }.onFailure { Log.w(TAG, "my_builds not saved: ${it.message}") }
    }

    private companion object {
        const val FILE_NAME = "my_builds.json"
        const val TAG = "Droidputter"
    }
}
