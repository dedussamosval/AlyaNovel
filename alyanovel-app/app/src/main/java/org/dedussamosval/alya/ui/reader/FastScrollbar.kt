package org.dedussamosval.alya.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ползунок быстрой прокрутки у правого края: тяните — список прыгает к нужному месту.
 * Появляется при прокрутке и через ~1 с после остановки плавно исчезает.
 * label(индекс, всего) — подпись в «облачке» рядом с ползунком, пока вы его тянете.
 */
@Composable
fun BoxScope.FastScrollbar(
    state: LazyListState,
    label: (index: Int, total: Int) -> String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var trackPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }

    val total = state.layoutInfo.totalItemsCount
    val scrollFraction by remember(state) {
        derivedStateOf {
            val info = state.layoutInfo
            val maxFirst = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / maxFirst).coerceIn(0f, 1f)
        }
    }

    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) shown = true else { delay(1000); shown = false }
    }
    val alpha by animateFloatAsState(if (shown || dragging) 1f else 0f, label = "fastScrollbar")
    if (total < 3 || alpha == 0f) return // короткой главе ползунок не нужен; невидимый не рисуем

    val fraction = if (dragging) dragFraction else scrollFraction
    val thumbPx = with(LocalDensity.current) { 56.dp.toPx() }
    val thumbY = ((trackPx - thumbPx).coerceAtLeast(0f) * fraction).roundToInt()

    fun jumpTo(y: Float) {
        val f = (y / trackPx.coerceAtLeast(1)).coerceIn(0f, 1f)
        dragFraction = f
        scope.launch { state.scrollToItem((f * (total - 1)).roundToInt()) }
    }

    Box(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(36.dp)              // полоса для захвата пальцем у самого края
            .onSizeChanged { trackPx = it.height }
            .alpha(alpha)
            .pointerInput(total) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true; jumpTo(it.y) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ -> change.consume(); jumpTo(change.position.y) },
                )
            },
    ) {
        Box( // сам ползунок
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbY) }
                .padding(end = 4.dp)
                .size(width = 6.dp, height = 56.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }

    if (dragging) { // облачко «12 / 40» слева от ползунка
        val index = (dragFraction * (total - 1)).roundToInt()
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 48.dp)
                .offset { IntOffset(0, thumbY) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                text = label(index, total),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
