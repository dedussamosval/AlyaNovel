package org.dedussamosval.alya.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.dedussamosval.alya.App
import org.dedussamosval.alya.data.Bookmark
import org.dedussamosval.alya.data.Prefs
import org.dedussamosval.alya.data.ReaderPosition
import org.dedussamosval.alya.ui.appViewModel
import org.dedussamosval.alya.ui.components.EmptyState

/** Зазор между страницами манги (цвет — фон темы). 0.dp — страницы вплотную, как раньше. */
private val MANGA_PAGE_GAP = 6.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(chapterId: Int, onBack: () -> Unit, onOpenChapter: (Int) -> Unit) {
    val vm = appViewModel(key = "reader$chapterId") { ReaderViewModel(it, chapterId) }
    val state by vm.state.collectAsState()
    val container = (LocalContext.current.applicationContext as App).container
    val prefs by container.settings.flow.collectAsState(initial = Prefs())

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var savedPosition by remember(chapterId) {
        mutableStateOf<ReaderPosition?>(null)
    }
    var positionLoaded by remember(chapterId) {
        mutableStateOf(false)
    }

    LaunchedEffect(chapterId) {
        savedPosition = vm.getReaderPosition()
        positionLoaded = true
    }

    val title = vm.chapter?.let { "Том ${it.volumeNumber} · ${it.label}" } ?: "Глава"

    // Кнопка «Aa» — только для текстовых глав (у манги шрифта нет)
    val canStyleText = (state as? ReaderState.Ready)?.isManga == false
    var showTextSettings by rememberSaveable { mutableStateOf(false) }

    val bookmarks by vm.bookmarks.collectAsState()
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    var bookmarkTarget by remember { mutableStateOf<Int?>(null) }


    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { showBookmarks = true }) {
                        Icon(Icons.Rounded.Bookmarks, contentDescription = "Закладки")
                    }

                    IconButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "Вернуться наверх",
                        )
                    }

                    if (canStyleText) {
                        IconButton(onClick = { showTextSettings = true }) {
                            Icon(Icons.Rounded.TextFields, contentDescription = "Шрифт и размер текста")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is ReaderState.Loading -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val p = s.progress
                    if (p != null) CircularProgressIndicator(progress = { p }) else CircularProgressIndicator()
                    Text(
                        if (p != null) "Загрузка главы…" else "Открываем…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is ReaderState.Error -> Box(Modifier.padding(16.dp).align(Alignment.Center)) {
                    EmptyState(title = "Не удалось открыть главу", subtitle = s.message, actionLabel = "Назад", onAction = onBack)
                }

                is ReaderState.Ready -> ReaderContent(
                    state = s,
                    listState = listState,
                    savedPosition = savedPosition,
                    positionLoaded = positionLoaded,
                    onSavePosition = vm::saveReaderPosition,
                    onClearPosition = vm::clearReaderPosition,
                    chapterId = chapterId,
                    fontSize = prefs.fontSize,
                    fontFamily = fontFamilyOf(prefs.fontFamily),
                    lineSpacing = prefs.lineSpacing,
                    prev = vm.neighbors.first?.id,
                    next = vm.neighbors.second?.id,
                    onOpenChapter = onOpenChapter,
                    marked = remember(bookmarks) { bookmarks.mapTo(HashSet()) { it.itemIndex } },
                    onParagraphLongPress = { bookmarkTarget = it}
                )
            }
        }
    }

    if (showTextSettings && canStyleText) {
        TextSettingsSheet(
            prefs = prefs,
            onFontFamily = vm::setFontFamily,
            onFontSize = vm::setFontSize,
            onLineSpacing = vm::setLineSpacing,
            onDismiss = { showTextSettings = false },
        )
    }

    bookmarkTarget?.let { index ->
        val existing = bookmarks.firstOrNull { it.itemIndex == index }
        val text = ((vm.state.value as? ReaderState.Ready)?.items?.getOrNull(index) as? ReaderItem.Paragraph)?.text.orEmpty()
        AlertDialog(
            onDismissRequest = { bookmarkTarget = null },
            title = { Text(if (existing == null) "Добавить закладку?" else "Убрать закладку?") },
            text = { Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { vm.toggleBookmark(index); bookmarkTarget = null }) {
                    Text(if (existing == null) "Добавить" else "Убрать")
                }
            },
            dismissButton = { TextButton(onClick = { bookmarkTarget = null }) { Text("Отмена") } },
        )
    }

    if (showBookmarks) {
        val here = listState.firstVisibleItemIndex
        BookmarksSheet(
            items = bookmarks,
            hasHere = bookmarks.any { it.itemIndex == here },
            onToggleHere = { vm.toggleBookmark(here) },
            onJump = { b ->
                showBookmarks = false
                scope.launch {
                    vm.awaitItem(b.itemIndex)
                    listState.animateScrollToItem(b.itemIndex.coerceAtMost(listState.layoutInfo.totalItemsCount - 1))
                }
            },
            onDelete = vm::removeBookmark,
            onDismiss = { showBookmarks = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    items: List<Bookmark>, hasHere: Boolean,
    onToggleHere: () -> Unit, onJump: (Bookmark) -> Unit, onDelete: (Bookmark) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
            TextButton(onClick = onToggleHere, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(if (hasHere) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null)
            Text(if (hasHere) "  Убрать закладку отсюда" else "  Добавить закладку сюда")
        }
        if (items.isEmpty()) {
            Text("Закладок в этой главе пока нет", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(items, key = { it.id }) { b ->
                ListItem(
                    headlineContent = { Text(b.preview, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        IconButton(onClick = { onDelete(b) }) { Icon(Icons.Rounded.Delete, "Удалить") }
                    },
                    modifier = Modifier.clickable { onJump(b) },
                )
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ReaderContent(
    state: ReaderState.Ready,
    listState: LazyListState,
    savedPosition: ReaderPosition?,
    positionLoaded: Boolean,
    onSavePosition: (index: Int, offset: Int) -> Unit,
    onClearPosition: () -> Unit,
    chapterId: Int,
    fontSize: Int,
    fontFamily: FontFamily,
    lineSpacing: Int,
    prev: Int?,
    next: Int?,
    onOpenChapter: (Int) -> Unit,
    marked: Set<Int>,
    onParagraphLongPress: (Int) -> Unit,
) {
    // Манга — лента страниц во всю ширину без полей; ранобэ — текст с полями и скруглёнными иллюстрациями
    val sidePadding = if (state.isManga) 0.dp else 20.dp
//    val listState = rememberLazyListState()
    val pages = state.items.size

    var restored by remember(chapterId) {
        mutableStateOf(false)
    }
    // Зум для страниц с мангой и иллюстраций ранобэ
    val zoom = remember(chapterId) { ZoomState() }
    var fullImage by remember { mutableStateOf<ReaderItem.Picture?>(null) }

    val haptic = LocalHapticFeedback.current
    val longPress by rememberUpdatedState(onParagraphLongPress)

    LaunchedEffect(positionLoaded, savedPosition, state.items.size, state.done) {
        if (!positionLoaded || restored) return@LaunchedEffect

        val position = savedPosition

        // Для ранобэ ждём, пока загрузится сохранённый элемент.
        // Если глава уже загружена целиком, восстанавливаем позицию в любом случае.
        val canRestore =
            position == null ||
                    position.index < state.items.size ||
                    state.done

        if (canRestore) {
            if (position != null) {
                listState.scrollToItem(
                    index = position.index.coerceIn(0, state.items.size),
                    scrollOffset = position.offset,
                )
            }
            restored = true
        }
    }

    LaunchedEffect(chapterId, restored, state.done) {
        if (!restored) return@LaunchedEffect

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                state.done && !listState.canScrollForward,
            )
        }
            .distinctUntilChanged()
            .debounce(500)
            .collect { (index, offset, atEnd) ->
                if (atEnd) {
                    onClearPosition()
                } else {
                    onSavePosition(index, offset)
                }
            }
    }

    Box(
        Modifier.fillMaxSize().clipToBounds().then(
            if (state.isManga) Modifier
                .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
                .zoomGestures(zoom, listMode = true)
            else Modifier
        )
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(if (state.isManga) Modifier.zoomLayer(zoom) else Modifier),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (state.isManga) MANGA_PAGE_GAP else 14.dp),
        ) {
            itemsIndexed(state.items) { index, item ->
                when (item) {
                    is ReaderItem.Paragraph -> Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * lineSpacing / 100f).sp,
                            fontFamily = fontFamily,
                        ),
                        modifier = Modifier
                            .padding(horizontal = sidePadding)
                            .pointerInput(index) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val press = awaitLongPressOrCancellation(down.id)   // null: палец ушёл или это скролл
                                    if (press != null) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        longPress(index)
                                        waitForUpOrCancellation()?.consume()        // отпускание не должно срабатывать как тап
                                    }
                                }
                            }
                            .then(
                                if (index in marked) Modifier
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                    .padding(4.dp)
                                else Modifier
                            ),
                    )

                    is ReaderItem.Header -> {
                        val family = if (state.isManga) FontFamily.Default else fontFamily
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(item.label, style = MaterialTheme.typography.titleLarge.copy(fontFamily = family))
                            if (item.title.isNotBlank()) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = family))
                            }
                            if (item.date != null) {
                                Text(
                                    "Опубликовано: ${item.date}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = family),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is ReaderItem.Picture -> Column(Modifier.padding(horizontal = if (state.isManga) 0.dp else 12.dp)) {
                        AsyncImage(
                            model = if (state.isManga) hiRes(item.file) else item.file,   // манга — в высоком разрешении
                            contentDescription = item.caption.ifBlank { null },
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(item.aspect)
                                .clip(if (state.isManga) RectangleShape else MaterialTheme.shapes.medium)
                                .then(if (state.isManga) Modifier else Modifier.clickable { fullImage = item }),
                        )
                        if (item.caption.isNotBlank()) {
                            Text(
                                text = item.caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp),
                            )
                        }
                    }

                    is ReaderItem.Missing -> Text(
                        text = if (item.caption.isBlank()) "[иллюстрация недоступна]" else "[иллюстрация недоступна: ${item.caption}]",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = sidePadding),
                    )
                }
            }

            item(key = "footer") {
                if (!state.done) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = { prev?.let(onOpenChapter) },
                            enabled = prev != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("‹ Предыдущая") }
                        Button(
                            onClick = { next?.let(onOpenChapter) },
                            enabled = next != null,
                            modifier = Modifier.weight(1f),
                        ) { Text("Следующая ›") }
                    }
                }
            }
        }

        // Ползунок быстрой прокрутки у правого края: у манги — номер страницы, у ранобэ — проценты
        FastScrollbar(
            state = listState,
            label = { index, total ->
                if (state.isManga) "${index.coerceIn(1, pages)} / $pages"   // индекс 0 — шапка
                else "${(index * 100f / (total - 1).coerceAtLeast(1)).roundToInt()}%"
            },
        )

        fullImage?.let { FullImageDialog(it.file, it.caption) { fullImage = null } }

    }
}
