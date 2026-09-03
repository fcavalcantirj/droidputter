package com.droidputter.core.link

/** Instantaneous link throughput derived from two consecutive STATS frames. */
data class LinkRates(val fps: Double, val kbPerSec: Double, val dropped: Long)

/**
 * Turns the ESP's raw cumulative STATS counters (frames/bytes/dropped, sent once a second per
 * docs/PROTOCOL.md) into a per-interval rate. The ESP's counters only ever increase, so a
 * counter reset (e.g. after a reflash) would otherwise show as a huge negative delta -- clamped
 * to zero instead of a nonsensical negative fps/KB/s.
 */
class LinkStatsTracker {
    private var lastFrames: Long? = null
    private var lastBytes: Long? = null
    private var lastAtMs: Long? = null

    fun onStats(frames: Long, bytes: Long, dropped: Long, atMs: Long): LinkRates {
        val prevFrames = lastFrames
        val prevBytes = lastBytes
        val prevAtMs = lastAtMs
        val rates = if (prevFrames != null && prevBytes != null && prevAtMs != null && atMs > prevAtMs) {
            val dtSec = (atMs - prevAtMs) / 1000.0
            val deltaFrames = (frames - prevFrames).coerceAtLeast(0)
            val deltaBytes = (bytes - prevBytes).coerceAtLeast(0)
            LinkRates(fps = deltaFrames / dtSec, kbPerSec = (deltaBytes / 1024.0) / dtSec, dropped = dropped)
        } else {
            LinkRates(fps = 0.0, kbPerSec = 0.0, dropped = dropped)
        }
        lastFrames = frames
        lastBytes = bytes
        lastAtMs = atMs
        return rates
    }
}
