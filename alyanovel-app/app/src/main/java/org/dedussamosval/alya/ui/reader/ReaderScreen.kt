package org.dedussamosval.alya.ui.reader

import android.widget.Toast
import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.dedussamosval.alya.App
import org.dedussamosval.alya.data.Bookmark
import org.dedussamosval.alya.data.MANGA_PAGER_H
import org.dedussamosval.alya.data.MANGA_PAGER_V
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
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val prefs by container.settings.flow.collectAsState(initial = Prefs())

    val listState = rememberLazyListState()
    val ready = state as? ReaderState.Ready
    val isManga = ready?.isManga == true
    val pagerMode = isManga && (prefs.mangaMode == MANGA_PAGER_H || prefs.mangaMode == MANGA_PAGER_V)
    val itemCount = ready?.items?.size ?: 0
    val pagerState = rememberPagerState { itemCount + 1 }   // +1 — футер
    val scope = rememberCoroutineScope()

    fun currentIndex() = if (pagerMode) pagerState.currentPage else listState.firstVisibleItemIndex
    suspend fun jumpTo(i: Int) {
        if (pagerMode) pagerState.animateScrollToPage(i.coerceIn(0, itemCount))
        else listState.animateScrollToItem(i.coerceAtMost(listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    var showMenu by rememberSaveable { mutableStateOf(false) }
    var menuTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchPos by rememberSaveable { mutableIntStateOf(0) }

    val focusManager = LocalFocusManager.current
    var panelRect by remember { mutableStateOf(Rect.Zero) }
    var boxOffset by remember { mutableStateOf(Offset.Zero) }

    val canStyleText = ready?.isManga == false
    val q = query.trim()
    // Поиск — только по абзацам ранобэ; по мере догрузки текста список совпадений растёт
    val matches = remember(ready?.items, q) {
        if (!canStyleText || q.length < 2) emptyList()
        else ready?.items.orEmpty().mapIndexedNotNull { i, item ->
            if (item is ReaderItem.Paragraph && item.text.contains(q, ignoreCase = true)) i else null
        }
    }
    val activeMatch = matches.getOrNull(matchPos) ?: -1

    fun goMatch(step: Int) {
        if (matches.isEmpty()) return
        matchPos = (matchPos + step).mod(matches.size)
        scope.launch { jumpTo(matches[matchPos]) }
    }

    // Новый запрос → первое совпадение (с небольшой задержкой, пока печатаете)
    LaunchedEffect(q) {
        matchPos = 0
        if (q.length >= 2 && showMenu) {
            delay(350)
            matches.firstOrNull()?.let { jumpTo(it) }
        }
    }

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
                            scope.launch { jumpTo(0) }
//                            scope.launch { listState.animateScrollToItem(0) }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "Вернуться наверх",
                        )
                    }

                    if (ready != null) {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Настройки")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .onGloballyPositioned { boxOffset = it.positionInRoot() }
                .dismissOnOutsideTap(
                    active = showMenu,
                    isInside = { panelRect.contains(it + boxOffset) },
                    onDismiss = {
                        focusManager.clearFocus()   // убирает клавиатуру, если был открыт поиск
                        showMenu = false
                    },
                )
        ) {
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
                    onParagraphLongPress = { bookmarkTarget = it},
                    pagerState = pagerState,
                    mangaMode = prefs.mangaMode,
                    searchQuery = q,
                    activeMatch = activeMatch,
                )
            }
            AnimatedVisibility(
                visible = showMenu && ready != null,
                modifier = Modifier.align(Alignment.BottomCenter).imePadding(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                ReaderMenuPanel(
                    modifier = Modifier.onGloballyPositioned { panelRect = it.boundsInRoot() },
                    isManga = isManga,
                    loading = ready?.done == false,
                    prefs = prefs,
                    tab = menuTab,
                    onTab = { menuTab = it },
                    onFontFamily = vm::setFontFamily,
                    onFontSize = vm::setFontSize,
                    onLineSpacing = vm::setLineSpacing,
                    onMangaMode = vm::setMangaMode,
                    query = query,
                    onQuery = { query = it },
                    matchCount = matches.size,
                    matchPos = matchPos,
                    onStep = { goMatch(it) },
                    onClose = { showMenu = false },
                )
            }
        }
    }

    bookmarkTarget?.let { index ->
        val existing = bookmarks.firstOrNull { it.itemIndex == index }
        val text = (ready?.items?.getOrNull(index) as? ReaderItem.Paragraph)?.text.orEmpty()
        ParagraphMenu(
            bookmarked = existing != null,
            onToggleBookmark = { vm.toggleBookmark(index); bookmarkTarget = null },
            onCopy = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Текст главы", text))) }
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                }
                bookmarkTarget = null
            },
            onDismiss = { bookmarkTarget = null },
        )
    }

    if (showBookmarks) {
        val here = currentIndex()
        BookmarksSheet(
            items = bookmarks,
            hasHere = bookmarks.any { it.itemIndex == here },
            onToggleHere = { vm.toggleBookmark(here) },
            onJump = { b ->
                showBookmarks = false
                scope.launch { vm.awaitItem(b.itemIndex); jumpTo(b.itemIndex) }
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

@Composable
private fun ParagraphMenu(
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.width(250.dp).padding(vertical = 8.dp)) {
                MenuRow(
                    icon = if (bookmarked) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd,
                    label = if (bookmarked) "Убрать закладку" else "Добавить закладку",
                    onClick = onToggleBookmark,
                )
                MenuRow(Icons.Rounded.ContentCopy, "Копировать", onCopy)
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
    pagerState: PagerState,
    mangaMode: String,
    searchQuery: String,
    activeMatch: Int,
) {
    // Манга — лента страниц во всю ширину без полей; ранобэ — текст с полями и скруглёнными иллюстрациями
    val sidePadding = if (state.isManga) 0.dp else 20.dp
//    val listState = rememberLazyListState()
    val pages = state.items.size
    val pagerMode = state.isManga && (mangaMode == MANGA_PAGER_H || mangaMode == MANGA_PAGER_V)

    var restored by remember(chapterId) {
        mutableStateOf(false)
    }
    // Зум для страниц с мангой и иллюстраций ранобэ
    val zoom = remember(chapterId, listState) {
        ZoomState().also { z -> z.scrollBy = { d -> listState.dispatchRawDelta(d) } }
    }
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
                val idx = position.index.coerceIn(0, state.items.size)
                if (pagerMode) pagerState.scrollToPage(idx)
                else listState.scrollToItem(index = idx, scrollOffset = position.offset)
            }
            restored = true
        }
    }

    LaunchedEffect(chapterId, restored, state.done, pagerMode) {
        if (!restored) return@LaunchedEffect
        snapshotFlow {
            if (pagerMode) Triple(pagerState.currentPage, 0, state.done && pagerState.currentPage >= state.items.size)
            else Triple(
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                state.done && !listState.canScrollForward
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

    var prevPagerMode by remember(chapterId) { mutableStateOf(pagerMode) }
    LaunchedEffect(pagerMode) {
        if (pagerMode == prevPagerMode) return@LaunchedEffect
        if (restored) {
            if (pagerMode) pagerState.scrollToPage(listState.firstVisibleItemIndex)
            else listState.scrollToItem(pagerState.currentPage)
        }
        prevPagerMode = pagerMode
    }

    if (pagerMode) {
        MangaPager(
            state = state, pagerState = pagerState,
            vertical = mangaMode == MANGA_PAGER_V,
            prev = prev, next = next, onOpenChapter = onOpenChapter,
        )
    } else {
        Box(
            Modifier.fillMaxSize().clipToBounds().then(
                if (state.isManga) Modifier.zoomGestures(zoom, listMode = true) else Modifier
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
                        is ReaderItem.Paragraph -> {
                            val hl = MaterialTheme.colorScheme.primary.copy(alpha = if (index == activeMatch) 0.55f else 0.28f)
                            val shown = remember(item.text, searchQuery, hl) {
                                if (searchQuery.length >= 2) highlightMatches(item.text, searchQuery, hl)
                                else AnnotatedString(item.text)
                            }
                            Text(
                                text = shown,
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
                                            val press = awaitLongPressOrCancellation(down.id)
                                            if (press != null) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                longPress(index)
                                                waitForUpOrCancellation()?.consume()
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
                        }

                        is ReaderItem.Header -> {
                            val family = if (state.isManga) FontFamily.Default else fontFamily
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(item.label, style = MaterialTheme.typography.titleLarge.copy(fontFamily = family))
                                if (item.title.isNotBlank()) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = family)
                                    )
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
}
