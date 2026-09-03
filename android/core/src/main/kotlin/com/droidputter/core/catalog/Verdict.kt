package com.droidputter.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One person's report that a catalog build ran (or did not) on a board: the community "works / broken"
 * record. Tied to THAT firmware by [firmwareSha256] (the catalog's firmware.bin hash) and the shim commit the
 * catalog was generated from, so a new build never inherits an old verdict. Shared through
 * `apps/verdicts.json` in the repo; submitted as a prefilled GitHub issue from the phone.
 */
@Serializable
data class Verdict(
    val name: String,
    val env: String,
    @SerialName("firmware_sha256") val firmwareSha256: String,
    @SerialName("shim_commit") val shimCommit: String? = null,
    val board: String,
    /** "works" or "broken". */
    val result: String,
    val note: String = "",
    /** ISO date, e.g. 2026-09-03. */
    val date: String = "",
    val reporter: String? = null,
) {
    val works: Boolean get() = result == RESULT_WORKS

    companion object {
        const val RESULT_WORKS = "works"
        const val RESULT_BROKEN = "broken"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

        fun parseList(text: String): List<Verdict> =
            runCatching { json.decodeFromString(ListSerializer(serializer()), text) }.getOrDefault(emptyList())

        fun toJson(list: List<Verdict>): String = json.encodeToString(ListSerializer(serializer()), list)

        fun toJson(v: Verdict): String = json.encodeToString(serializer(), v)
    }
}

/** What the catalog shows next to an entry, derived from every verdict known for it. */
data class VerdictSummary(
    val status: Status,
    /** True when the verdicts are for exactly this firmware hash; false = an older build of the same app. */
    val sameVersion: Boolean,
    val worksCount: Int,
    val brokenCount: Int,
    /** True when the deciding verdict came from this device. */
    val own: Boolean,
) {
    enum class Status { WORKS, BROKEN, MIXED, UNTESTED }

    val label: String
        get() = when (status) {
            Status.UNTESTED -> "untested"
            Status.WORKS -> if (sameVersion) "works" else "older build worked"
            Status.BROKEN -> if (sameVersion) "broken" else "older build broken"
            Status.MIXED -> "mixed ($worksCount works / $brokenCount broken)"
        }
}

object VerdictMerge {
    /**
     * This device's own verdict for the same firmware wins; else the community verdicts for the same
     * firmware (majority; a tie is MIXED); else the newest community verdict for the same app name at any
     * version, flagged as an older build; else UNTESTED.
     */
    fun summarize(entry: CatalogEntry, remote: List<Verdict>, local: List<Verdict>): VerdictSummary {
        val sha = entry.firmwareSha256
        val mine = local.lastOrNull { it.name == entry.name && it.env == entry.env && it.firmwareSha256 == sha }
        if (mine != null) return VerdictSummary(if (mine.works) VerdictSummary.Status.WORKS else VerdictSummary.Status.BROKEN, true, if (mine.works) 1 else 0, if (mine.works) 0 else 1, own = true)
        val same = remote.filter { it.name == entry.name && it.env == entry.env && it.firmwareSha256 == sha }
        if (same.isNotEmpty()) {
            val w = same.count { it.works }; val b = same.size - w
            val status = when { w > b -> VerdictSummary.Status.WORKS; b > w -> VerdictSummary.Status.BROKEN; else -> VerdictSummary.Status.MIXED }
            return VerdictSummary(status, true, w, b, own = false)
        }
        val older = remote.filter { it.name == entry.name && it.env == entry.env }.maxByOrNull { it.date }
        if (older != null) return VerdictSummary(if (older.works) VerdictSummary.Status.WORKS else VerdictSummary.Status.BROKEN, false, if (older.works) 1 else 0, if (older.works) 0 else 1, own = false)
        return VerdictSummary(VerdictSummary.Status.UNTESTED, false, 0, 0, own = false)
    }

    /** The GitHub "new issue" URL that carries a verdict; the repo folds labelled issues into verdicts.json. */
    fun issueUrl(repo: String, v: Verdict): String {
        val title = "[verdict] ${v.name}/${v.env} ${v.result} on ${v.board} (${v.firmwareSha256.take(12)})"
        val body = "Reported from the Droidputter app.\n\n```json\n${Verdict.toJson(v)}\n```\n"
        return "https://github.com/$repo/issues/new?labels=verdict&title=${urlEncode(title)}&body=${urlEncode(body)}"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
