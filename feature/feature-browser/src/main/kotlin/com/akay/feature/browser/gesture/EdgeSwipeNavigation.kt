package com.akay.feature.browser.gesture

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Chrome-style edge swipe for browser back/forward, implemented as two thin
 * (18dp) invisible strips pinned to the left/right screen edges rather than
 * a gesture detector spanning the whole WebView. A full-surface detector
 * fights the WebView's native fling/scroll handling for every touch event
 * and makes page scrolling feel janky; a narrow edge-only strip leaves the
 * rest of the screen untouched by Compose's pointer input entirely.
 */
@Composable
fun EdgeSwipeOverlay(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onSwipeBack: () -> Unit,
    onSwipeForward: () -> Unit,
    modifier: Modifier = Modifier,
    edgeWidth: androidx.compose.ui.unit.Dp = 18.dp
) {
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 80.dp.toPx() } }

    Box(modifier = modifier.fillMaxSize()) {
        if (canGoBack) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(edgeWidth)
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, amount ->
                                totalDrag += amount
                                if (totalDrag > 0f) change.consume()
                            },
                            onDragEnd = {
                                if (totalDrag > thresholdPx) onSwipeBack()
                                totalDrag = 0f
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    }
            )
        }
        if (canGoForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(edgeWidth)
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, amount ->
                                totalDrag += amount
                                if (totalDrag < 0f) change.consume()
                            },
                            onDragEnd = {
                                if (-totalDrag > thresholdPx) onSwipeForward()
                                totalDrag = 0f
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    }
            )
        }
    }
}
