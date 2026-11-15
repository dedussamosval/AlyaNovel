package org.dedussamosval.alya.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.dedussamosval.alya.data.MANGA_PAGER_H
import org.dedussamosval.alya.data.MANGA_PAGER_V
import org.dedussamosval.alya.data.MANGA_STRIP
import org.dedussamosval.alya.ui.components.PillToggle

private val MODE_KEYS = listOf(MANGA_STRIP, MANGA_PAGER_H, MANGA_PAGER_V)
private val MODE_LABELS = listOf("Лентой", "По одной →", "По одной ↓")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaSettingsSheet(mode: String, onMode: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Режим чтения", style = MaterialTheme.typography.titleLarge)
            PillToggle(
                options = MODE_LABELS,
                selected = MODE_KEYS.indexOf(mode).coerceAtLeast(0),
                onSelect = { onMode(MODE_KEYS[it]) },
            )
            Text(
                when (mode) {
                    MANGA_PAGER_H -> "Страницы листаются свайпом влево и вправо, по одной на экран."
                    MANGA_PAGER_V -> "Страницы листаются свайпом вверх и вниз, по одной на экран."
                    else -> "Все страницы идут сплошной лентой."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Режимы «по одной». Индексы страниц те же, что у LazyColumn: 0 — шапка, 1..N — страницы, N+1 — футер. */
@Composable
fun MangaPager(
    state: ReaderState.Ready,
    pagerState: PagerState,
    vertical: Boolean,
    prev: Int?, next: Int?, onOpenChapter: (Int) -> Unit,
) {
    val pages = (state.items.size - 1).coerceAtLeast(1)
    Box(Modifier.fillMaxSize()) {
        val content: @Composable (Int) -> Unit = { index ->
            when (val item = state.items.getOrNull(index)) {
                is ReaderItem.Header -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(item.label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    item.date?.let {
                        Text("Опубликовано: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is ReaderItem.Picture -> ZoomablePage(item)
                null -> Column(   // index == items.size: футер
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Button(onClick = { next?.let(onOpenChapter) }, enabled = next != null, modifier = Modifier.fillMaxWidth()) { Text("Следующая ›") }
                    OutlinedButton(onClick = { prev?.let(onOpenChapter) }, enabled = prev != null, modifier = Modifier.fillMaxWidth()) { Text("‹ Предыдущая") }
                }
                else -> {}
            }
        }
        if (vertical) VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { content(it) }
        else HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { content(it) }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Text(
                "${pagerState.currentPage.coerceIn(1, pages)} / $pages",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ZoomablePage(item: ReaderItem.Picture) {
    val zoom = remember(item.file) { ZoomState() }
    Box(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.background)) {
        AsyncImage(
            model = hiRes(item.file),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
                .zoomGestures(zoom, listMode = false)   // при scale == 1 одиночный палец не потребляется, пейджер листает
                .graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale
                    translationX = zoom.offsetX; translationY = zoom.offsetY
                },
        )
    }
}