package com.droidputter.core.gps

/**
 * Android's `OnNmeaMessageListener` hands out raw sentences straight from the GNSS chip,
 * which on a multi-constellation fix use the `$GN` talker id (e.g. `$GNGGA`) instead of the
 * `$GP` a GPS-only receiver would send. Pense-Bem-era apps and TinyGPSPlus only recognize
 * `$GP*`/`$GL*`/... talker ids they were written against; this rewrites `$GN` to `$GP` so a
 * passthrough sentence decodes the same way a synthesized one (see [NmeaSynth]) would.
 */
object NmeaNormalizer {
    /** Returns [sentence] with its talker id rewritten from `GN` to `GP` and its checksum
     * recomputed to match (the talker id is part of the checksummed body); a sentence that
     * doesn't start with `$GN` is returned unchanged. */
    fun normalize(sentence: String): String {
        val trimmed = sentence.trim()
        if (!trimmed.startsWith("\$GN")) return trimmed
        val star = trimmed.indexOf('*')
        val body = if (star >= 0) trimmed.substring(1, star) else trimmed.substring(1)
        val rewritten = "GP" + body.substring(2)
        return NmeaSynth.withChecksum(rewritten)
    }
}
