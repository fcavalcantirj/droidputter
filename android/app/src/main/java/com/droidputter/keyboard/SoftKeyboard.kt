package com.droidputter.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidputter.core.keys.CardputerKeyMap

private val FN_POS = 2 to 0
private val SHIFT_POS = 2 to 1

/**
 * On-screen Cardputer 4x14 grid. Ordinary keys send KEY down on touch-down and KEY up on
 * release (matching a physical key). fn/shift can't be physically held by a tapping finger the
 * way they can on real hardware, so they're sticky here: a tap latches KEY down and keeps it
 * down (legends switch to their shifted form) until tapped again, which sends KEY up.
 * Rows are a fixed [keyHeight] (not square keys): on a 2712 px-wide landscape phone square keys
 * were 194 px tall and ate 64% of the screen, leaving the mirror at 2x.
 */
@Composable
fun SoftKeyboard(
    onKey: (row: Int, col: Int, down: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    keyHeight: Dp = 34.dp,
) {
    var fnLatched by remember { mutableStateOf(false) }
    var shiftLatched by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val rows = remember { CardputerKeyMap.KEYS.groupBy { it.row }.toSortedMap() }

    androidx.compose.foundation.layout.Column(modifier = modifier.background(Color(0xFF1A1A1A)).padding(2.dp)) {
        rows.forEach { (_, keysInRow) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                keysInRow.sortedBy { it.col }.forEach { key ->
                    val isFn = (key.row to key.col) == FN_POS
                    val isShift = (key.row to key.col) == SHIFT_POS
                    val latched = (isFn && fnLatched) || (isShift && shiftLatched)
                    val legend = if (shiftLatched) key.shiftedLegend else key.legend
                    KeyCap(
                        legend = legend,
                        latched = latched,
                        modifier = Modifier.weight(1f).height(keyHeight).pointerInput(key.row, key.col) {
                            detectTapGestures(
                                onPress = {
                                    when {
                                        isFn -> {
                                            fnLatched = !fnLatched
                                            onKey(key.row, key.col, fnLatched)
                                        }
                                        isShift -> {
                                            shiftLatched = !shiftLatched
                                            onKey(key.row, key.col, shiftLatched)
                                        }
                                        else -> {
                                            onKey(key.row, key.col, true)
                                            try {
                                                tryAwaitRelease()
                                            } finally {
                                                onKey(key.row, key.col, false)
                                            }
                                        }
                                    }
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyCap(legend: String, latched: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .padding(1.dp)
            .background(if (latched) Color(0xFF3D6BFF) else Color(0xFF2E2E2E)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = legend,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.wrapContentHeight(),
        )
    }
}
