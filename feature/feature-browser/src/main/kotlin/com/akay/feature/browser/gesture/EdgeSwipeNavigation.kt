package com.akay.feature.browser.gesture

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Chrome-style edge swipe: a horizontal drag that starts near the left or
 * right edge of the screen triggers back/forward navigation once it passes
 * a distance threshold. Drags that start away from the edges (e.g. normal
 * page scrolling/zooming) are ignored.
 */
fun Modifier.edgeSwipeNavigation(
    enabled: Boolean = true,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onSwipeBack: () -> Unit,
    onSwipeForward: () -> Unit
): Modifier = composed {
    if (!enabled) return@composed this
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { 24.dp.toPx() }
    val thresholdPx = with(density) { 80.dp.toPx() }

    this.pointerInput(canGoBack, canGoForward) {
        var startX = 0f
        var startedAtLeftEdge = false
        var startedAtRightEdge = false
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                startX = offset.x
                totalDrag = 0f
                startedAtLeftEdge = offset.x <= edgeWidthPx
                startedAtRightEdge = offset.x >= size.width - edgeWidthPx
            },
            onHorizontalDrag = { change, dragAmount ->
                totalDrag += dragAmount
                if ((startedAtLeftEdge && totalDrag > 0) || (startedAtRightEdge && totalDrag < 0)) {
                    change.consume()
                }
            },
            onDragEnd = {
                if (startedAtLeftEdge && totalDrag > thresholdPx && canGoBack) {
                    onSwipeBack()
                } else if (startedAtRightEdge && -totalDrag > thresholdPx && canGoForward) {
                    onSwipeForward()
                }
                totalDrag = 0f
            },
            onDragCancel = { totalDrag = 0f }
        )
    }
}
