package org.dedussamosval.alya.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.dedussamosval.alya.data.Chapter
import org.dedussamosval.alya.data.KIND_MANGA
import org.dedussamosval.alya.data.KIND_RANOBE
import org.dedussamosval.alya.data.TYPE_EXTRA
import org.dedussamosval.alya.data.Volume
import org.dedussamosval.alya.ui.appViewModel
import org.dedussamosval.alya.ui.components.EmptyState
import org.dedussamosval.alya.ui.components.Hero
import org.dedussamosval.alya.ui.components.PillToggle
import org.dedussamosval.alya.ui.components.SiteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(listState: LazyListState, onOpenChapter: (Int) -> Unit, snackbar: SnackbarHostState) {
    val vm = appViewModel { HomeViewModel(it) }
    val ui by vm.ui.collectAsState()

    // Пустой набор при первом запуске: все тома свернуты.
    var expandedVolumeIds by rememberSaveable(ui.kind) {
        mutableStateOf(emptySet<Int>())
    }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshDownloaded() }

    PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                Hero(
                    title = "AlyaNovel",
                    subtitle = "Читайте новеллу и мангу: тома и главы, офлайн-чтение и чат читателей",
                )
            }
            item(key = "toggle") {
                PillToggle(
                    options = listOf("Манга", "Ранобэ"),
                    selected = if (ui.kind == KIND_MANGA) 0 else 1,
                    onSelect = { vm.selectKind(if (it == 0) KIND_MANGA else KIND_RANOBE) },
                )
            }
            if (ui.offline) {
                item(key = "offline") {
                    Text(
                        "Офлайн-режим: показан сохранённый список",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            when {
                ui.loading && ui.volumes.isEmpty() -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                ui.error != null && ui.volumes.isEmpty() -> item(key = "error") {
                    EmptyState(
                        title = "Не удалось подключиться к серверу",
                        subtitle = ui.error,
                        actionLabel = "Повторить",
                        onAction = { vm.refresh() },
                    )
                }
                ui.volumes.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        title = "Пока ничего нет",
                        subtitle = "Главы появятся здесь, когда их загрузят на сервер",
                    )
                }
                else -> items(ui.volumes, key = { it.id }) { volume ->
                    VolumeCard(
                        volume = volume,
                        ui = ui,
                        expanded = volume.id in expandedVolumeIds,
                        onToggle = {
                            expandedVolumeIds =
                                if (volume.id in expandedVolumeIds) {
                                    expandedVolumeIds - volume.id
                                } else {
                                    expandedVolumeIds + volume.id
                                }
                        },
                        onOpen = onOpenChapter,
                        onDownload = { vm.download(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(
    volume: Volume,
    ui: HomeUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (Int) -> Unit,
    onDownload: (Chapter) -> Unit,
) {
    SiteCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = buildString {
                        append("Том ${volume.number}")
                        if (volume.title.isNotBlank()) append(" · ${volume.title}")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${volume.chapters.size} глав",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = if (expanded) "Свернуть том" else "Развернуть том",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            volume.chapters.forEachIndexed { index, ch ->
                if (ch.type == TYPE_EXTRA &&
                    volume.chapters.getOrNull(index - 1)?.type != TYPE_EXTRA
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Дополнительные главы",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ChapterRow(
                    chapter = ch,
                    read = ch.id in ui.readIds,
                    downloaded = ch.id in ui.downloaded,
                    progress = ui.progress[ch.id],
                    onOpen = { onOpen(ch.id) },
                    onDownload = { onDownload(ch) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    read: Boolean,
    downloaded: Boolean,
    progress: Float?,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                chapter.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (read) muted else MaterialTheme.colorScheme.onSurface,
            )
            if (chapter.title.isNotBlank()) {
                Text(chapter.title, style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
        when {
            progress != null -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
            downloaded -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.DownloadDone,
                    contentDescription = "Скачано",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            else -> IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = "Скачать для офлайн-чтения", tint = muted)
            }
        }
    }
}
