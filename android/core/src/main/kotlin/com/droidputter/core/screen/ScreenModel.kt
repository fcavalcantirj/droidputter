package com.droidputter.core.screen

import com.droidputter.core.protocol.DpMessage

/** Rectangle touched by the last [ScreenModel.apply] call, in framebuffer coordinates,
 * already clipped to the framebuffer bounds. */
data class DirtyRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * RGB565 framebuffer (one Int per pixel, 0..65535) driven by decoded [DpMessage]s.
 * Resizes on HELLO; FILL/RECT/RECT_RLE are clipped to the current bounds instead of
 * throwing on an out-of-range write (a real device can send a stale rect mid-resize).
 */
class ScreenModel(width: Int = 240, height: Int = 135) {
    var width: Int = width
        private set
    var height: Int = height
        private set

    private var framebuffer = IntArray(width * height)

    /** Count of writes clipped (partially or fully out of bounds), cumulative. */
    var clippedWrites: Int = 0
        private set

    fun resize(w: Int, h: Int) {
        width = w
        height = h
        framebuffer = IntArray(w * h)
    }

    /** Applies [msg] to the framebuffer, returning the touched (clipped) region, or null
     * if the message carries no pixels (HELLO, STATS) or was entirely out of bounds. */
    fun apply(msg: DpMessage): DirtyRect? = when (msg) {
        is DpMessage.Hello -> {
            resize(msg.w, msg.h)
            null
        }
        is DpMessage.Fill -> putRect(msg.x, msg.y, msg.w, msg.h) { _, _ -> msg.color }
        is DpMessage.Rect -> putRect(msg.x, msg.y, msg.w, msg.h) { lx, ly -> msg.pixels[ly * msg.w + lx] }
        is DpMessage.RectRle -> putRect(msg.x, msg.y, msg.w, msg.h) { lx, ly -> msg.pixels[ly * msg.w + lx] }
        is DpMessage.Stats -> null
    }

    /** Copy of the current framebuffer (safe to hold onto after further [apply] calls). */
    fun snapshot(): IntArray = framebuffer.copyOf()

    private inline fun putRect(x: Int, y: Int, w: Int, h: Int, pixelAt: (Int, Int) -> Int): DirtyRect? {
        val clipW = minOf(w, width - x)
        val clipH = minOf(h, height - y)
        if (x < 0 || y < 0 || clipW <= 0 || clipH <= 0) {
            clippedWrites++
            return null
        }
        if (clipW < w || clipH < h) clippedWrites++
        for (ly in 0 until clipH) {
            val row = (y + ly) * width + x
            for (lx in 0 until clipW) {
                framebuffer[row + lx] = pixelAt(lx, ly)
            }
        }
        return DirtyRect(x, y, clipW, clipH)
    }
}
