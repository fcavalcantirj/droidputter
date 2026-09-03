package com.droidputter.core.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LinkStatsTest {
    @Test
    fun `first sample has no prior baseline so rates are zero`() {
        val tracker = LinkStatsTracker()

        val rates = tracker.onStats(frames = 10, bytes = 6_480, dropped = 3, atMs = 1_000)

        assertEquals(0.0, rates.fps)
        assertEquals(0.0, rates.kbPerSec)
        assertEquals(3, rates.dropped)
    }

    @Test
    fun `second sample one second later derives fps and KBps from the delta`() {
        val tracker = LinkStatsTracker()
        tracker.onStats(frames = 10, bytes = 10_240, dropped = 0, atMs = 1_000)

        val rates = tracker.onStats(frames = 24, bytes = 20_480, dropped = 2, atMs = 2_000)

        assertEquals(14.0, rates.fps)
        assertEquals(10.0, rates.kbPerSec)
        assertEquals(2, rates.dropped)
    }

    @Test
    fun `half second interval scales the delta up to a per-second rate`() {
        val tracker = LinkStatsTracker()
        tracker.onStats(frames = 0, bytes = 0, dropped = 0, atMs = 1_000)

        val rates = tracker.onStats(frames = 5, bytes = 512, dropped = 0, atMs = 1_500)

        assertEquals(10.0, rates.fps)
        assertEquals(1.0, rates.kbPerSec)
    }

    @Test
    fun `a counter reset after a reflash clamps to zero instead of going negative`() {
        val tracker = LinkStatsTracker()
        tracker.onStats(frames = 500, bytes = 200_000, dropped = 9, atMs = 1_000)

        val rates = tracker.onStats(frames = 2, bytes = 100, dropped = 0, atMs = 2_000)

        assertEquals(0.0, rates.fps)
        assertEquals(0.0, rates.kbPerSec)
        assertEquals(0, rates.dropped)
    }

    @Test
    fun `a non-positive elapsed time is treated like a fresh baseline`() {
        val tracker = LinkStatsTracker()
        tracker.onStats(frames = 10, bytes = 1_000, dropped = 0, atMs = 1_000)

        val rates = tracker.onStats(frames = 11, bytes = 1_100, dropped = 0, atMs = 1_000)

        assertEquals(0.0, rates.fps)
        assertEquals(0.0, rates.kbPerSec)
    }
}
