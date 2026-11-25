package io.github.chibisueru.telegramdrive.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What a scrollbar needs to know about a lazy list or grid. */
private class ScrollInfo(
    val total: Int,
    val visible: Int,
    /** Index of the first visible item, with the scrolled-off part of it as a fraction. */
    val first: Float,
    val scrolling: Boolean,
)

@Composable
fun VerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            val firstItem = layout.visibleItemsInfo.firstOrNull()
            val partial = if (firstItem != null && firstItem.size > 0) state.firstVisibleItemScrollOffset.toFloat() / firstItem.size else 0f
            ScrollInfo(layout.totalItemsCount, layout.visibleItemsInfo.size, state.firstVisibleItemIndex + partial, state.isScrollInProgress)
        }
    }
    Scrollbar(info, modifier) { state.scrollToItem(it) }
}

@Composable
fun VerticalScrollbar(state: LazyGridState, modifier: Modifier = Modifier) {
    val info by remember(state) {
        derivedStateOf {
            val layout = state.layoutInfo
            ScrollInfo(layout.totalItemsCount, layout.visibleItemsInfo.size, state.firstVisibleItemIndex.toFloat(), state.isScrollInProgress)
        }
    }
    Scrollbar(info, modifier) { state.scrollToItem(it) }
}

/**
 * A thin thumb at the right edge that shows where you are and can be dragged to jump through
 * long folders. Faint while idle, solid while scrolling or dragging; hidden if everything fits.
 */
@Composable
private fun Scrollbar(info: ScrollInfo, modifier: Modifier, scrollTo: suspend (Int) -> Unit) {
    val scrollable = info.total > info.visible && info.visible > 0
    if (!scrollable) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(info.scrolling, dragging) {
        if (info.scrolling || dragging) recentlyScrolled = true
        else { delay(1200); recentlyScrolled = false }
    }
    val alpha by animateFloatAsState(if (recentlyScrolled || dragging) 0.9f else 0.35f, label = "scrollbar-alpha")
    val thumbWidth by animateDpAsState(if (dragging) 10.dp else 6.dp, label = "scrollbar-width")
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxHeight().width(28.dp)) {
        val trackPx = constraints.maxHeight.toFloat()
        val minThumb = with(density) { 48.dp.toPx() }
        val thumbPx = (trackPx * info.visible / info.total).coerceIn(minThumb, trackPx)
        val maxFirst = (info.total - info.visible).coerceAtLeast(1)
        val progress = (info.first / maxFirst).coerceIn(0f, 1f)
        val thumbTop = (trackPx - thumbPx) * progress

        fun jumpTo(y: Float) {
            val fraction = ((y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
            scope.launch { scrollTo((fraction * maxFirst).roundToInt()) }
        }

        Box(
            Modifier
                .fillMaxHeight()
                .width(28.dp)
                .pointerInput(trackPx, thumbPx, maxFirst) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jumpTo(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> change.consume(); jumpTo(change.position.y) }
                }
                .pointerInput(trackPx, thumbPx, maxFirst) { detectTapGestures { jumpTo(it.y) } },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbTop.roundToInt()) }
                    .padding(end = 4.dp)
                    .width(thumbWidth)
                    .height(with(density) { thumbPx.toDp() })
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)),
            )
        }
    }
}
