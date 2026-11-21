package org.dedussamosval.alya.ui.reader

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Режимы «по одной». Индексы страниц те же, что у LazyColumn: 0 — шапка, 1..N — страницы, N+1 — футер. */
@Composable
fun MangaPager(
    state: ReaderState.Ready,
    pagerState: PagerState,
    vertical: Boolean,
    prev: Int?, next: Int?, onOpenChapter: (Int) -> Unit,
) {
    val pages = (state.items.size - 1).coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    var showJump by remember { mutableStateOf(false) }
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
            onClick = { showJump = true },
            enabled = pages > 1,
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

        if (showJump) {
            PageJumpDialog(
                current = pagerState.currentPage.coerceIn(1, pages),
                pages = pages,
                onJump = { page ->
                    showJump = false
                    scope.launch { pagerState.scrollToPage(page) }   // индекс пейджера = номер страницы (0 — шапка)
                },
                onDismiss = { showJump = false },
            )
        }
    }
}

@Composable
private fun PageJumpDialog(current: Int, pages: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableIntStateOf(current) }
    var text by remember { mutableStateOf(current.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перейти к странице") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t ->
                        val digits = t.filter(Char::isDigit).take(4)
                        text = digits
                        digits.toIntOrNull()?.let { page = it.coerceIn(1, pages) }
                    },
                    singleLine = true,
                    label = { Text("Страница (1–$pages)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onJump(page) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Slider(
                    value = page.toFloat(),
                    onValueChange = { page = it.roundToInt(); text = page.toString() },
                    valueRange = 1f..pages.toFloat(),
                    steps = (pages - 2).coerceAtLeast(0),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onJump(page) }) { Text("Перейти") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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
                .zoomGestures(zoom, listMode = false)   // при scale == 1 одиночный палец не потребляется, пейджер листает
                .graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale
                    translationX = zoom.offsetX; translationY = zoom.offsetY
                },
        )
    }
}