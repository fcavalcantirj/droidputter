package com.droidputter.render

import android.graphics.Bitmap
import com.droidputter.core.screen.DirtyRect

/**
 * Mirrors an RGB565-packed pixel buffer into a reused ARGB_8888 [Bitmap], converting and
 * uploading only the pixels inside a [DirtyRect] on each [update] call so a burst of small
 * partial redraws (the common case -- keystroke-driven text apps) stays cheap.
 */
class Framebuffer(width: Int, height: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private var scratch = IntArray(width * height)

    fun update(pixels: IntArray, sourceWidth: Int, dirty: DirtyRect) {
        val n = dirty.w * dirty.h
        if (n > scratch.size) scratch = IntArray(n)
        var i = 0
        for (ly in 0 until dirty.h) {
            val row = (dirty.y + ly) * sourceWidth + dirty.x
            for (lx in 0 until dirty.w) {
                scratch[i++] = rgb565ToArgb(pixels[row + lx])
            }
        }
        bitmap.setPixels(scratch, 0, dirty.w, dirty.x, dirty.y, dirty.w, dirty.h)
    }
}

/** Bit-replication expansion (5/6-bit channel -> 8-bit) so 0 stays 0 and max stays 255. */
private fun rgb565ToArgb(v: Int): Int {
    val r5 = (v shr 11) and 0x1F
    val g6 = (v shr 5) and 0x3F
    val b5 = v and 0x1F
    val r8 = (r5 shl 3) or (r5 shr 2)
    val g8 = (g6 shl 2) or (g6 shr 4)
    val b8 = (b5 shl 3) or (b5 shr 2)
    return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
}
