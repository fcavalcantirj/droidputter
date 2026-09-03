package com.droidputter.render

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droidputter.core.protocol.DpMessage
import com.droidputter.core.screen.ScreenModel

private const val MIN_FRAME_INTERVAL_MS = 16L // ~60 Hz cap on recomposition

/**
 * Applies decoded [DpMessage]s to a [ScreenModel] and mirrors the result into a Compose-
 * observable [Bitmap]. Every message still lands on the bitmap immediately (correctness);
 * only [version] -- the recomposition trigger a composable reads -- is throttled to
 * [MIN_FRAME_INTERVAL_MS], so a burst of small RECTs doesn't invalidate faster than the
 * display can show.
 */
class ScreenController(initialWidth: Int = 240, initialHeight: Int = 135) {
    private val screenModel = ScreenModel(initialWidth, initialHeight)
    private var framebuffer = Framebuffer(initialWidth, initialHeight)
    private var lastEmitMs = 0L

    val bitmap: Bitmap get() = framebuffer.bitmap

    var version: Int by mutableIntStateOf(0)
        private set

    var hello: DpMessage.Hello? by mutableStateOf(null)
        private set

    fun onMessage(msg: DpMessage) {
        if (msg is DpMessage.Hello) {
            hello = msg
            framebuffer = Framebuffer(msg.w, msg.h)
        }
        val dirty = screenModel.apply(msg) ?: return
        framebuffer.update(screenModel.snapshot(), screenModel.width, dirty)
        val now = System.currentTimeMillis()
        if (now - lastEmitMs >= MIN_FRAME_INTERVAL_MS) {
            lastEmitMs = now
            version++
        }
    }
}
