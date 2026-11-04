package org.dedussamosval.alya.ui.reader

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import org.dedussamosval.alya.App
import org.dedussamosval.alya.data.Prefs
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

    val title = vm.chapter?.let { "Том ${it.volumeNumber} · ${it.label}" } ?: "Глава"

    // Кнопка «Aa» — только для текстовых глав (у манги шрифта нет)
    val canStyleText = (state as? ReaderState.Ready)?.isManga == false
    var showTextSettings by rememberSaveable { mutableStateOf(false) }

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
                    fontSize = prefs.fontSize,
                    fontFamily = fontFamilyOf(prefs.fontFamily),
                    lineSpacing = prefs.lineSpacing,
                    prev = vm.neighbors.first?.id,
                    next = vm.neighbors.second?.id,
                    onOpenChapter = onOpenChapter,
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
}

@Composable
private fun ReaderContent(
    state: ReaderState.Ready,
    fontSize: Int,
    fontFamily: FontFamily,
    lineSpacing: Int,
    prev: Int?,
    next: Int?,
    onOpenChapter: (Int) -> Unit,
) {
    // Манга — лента страниц во всю ширину без полей; ранобэ — текст с полями и скруглёнными иллюстрациями
    val sidePadding = if (state.isManga) 0.dp else 20.dp
    val listState = rememberLazyListState()
    val pages = state.items.count { it is ReaderItem.Picture }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (state.isManga) MANGA_PAGE_GAP else 14.dp),
        ) {
            itemsIndexed(state.items) { _, item ->
                when (item) {
                    is ReaderItem.Paragraph -> Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * lineSpacing / 100f).sp,
                            fontFamily = fontFamily,
                        ),
                        modifier = Modifier.padding(horizontal = sidePadding),
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
                            model = item.file,
                            contentDescription = item.caption.ifBlank { null },
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(item.aspect)
                                .clip(if (state.isManga) androidx.compose.ui.graphics.RectangleShape else MaterialTheme.shapes.medium),
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
    }
}
