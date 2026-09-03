package com.droidputter.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen letterboxed view of a [ScreenController]'s framebuffer, with a thin status
 * bar naming the linked board/app (from HELLO) above it. */
@Composable
fun DroidputterScreen(controller: ScreenController, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        StatusBar(controller)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            FramebufferCanvas(controller, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun StatusBar(controller: ScreenController) {
    val hello = controller.hello
    val label = if (hello != null) "${hello.board} / ${hello.app}" else "no signal"
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.DarkGray)
            .height(20.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 6.dp),
    )
}

/** Draws [ScreenController.bitmap] scaled by an integer nearest-neighbour factor (crisp
 * pixels, no blur) and centered (letterboxed) inside the available space. */
@Composable
private fun FramebufferCanvas(controller: ScreenController, modifier: Modifier = Modifier) {
    controller.version // subscribe: recomposition is driven by this, throttled in ScreenController
    val image = controller.bitmap.asImageBitmap()
    Canvas(modifier = modifier) {
        val bw = image.width
        val bh = image.height
        if (bw <= 0 || bh <= 0) return@Canvas
        val scale = maxOf(1, minOf((size.width / bw).toInt(), (size.height / bh).toInt()))
        val dstW = bw * scale
        val dstH = bh * scale
        drawImage(
            image = image,
            dstOffset = IntOffset(((size.width - dstW) / 2f).toInt(), ((size.height - dstH) / 2f).toInt()),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None,
        )
    }
}
