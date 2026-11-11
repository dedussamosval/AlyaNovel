package org.dedussamosval.alya.ui.notices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dedussamosval.alya.App
import org.dedussamosval.alya.data.NOTICE_CHANGELOG
import org.dedussamosval.alya.data.Notice
import org.dedussamosval.alya.data.Notices
import org.dedussamosval.alya.ui.components.EmptyState
import org.dedussamosval.alya.ui.components.PrimaryButton
import org.dedussamosval.alya.ui.components.SiteCard
import org.dedussamosval.alya.ui.components.openUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter
    .ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))
    .withZone(ZoneId.systemDefault())

@Composable
fun NoticesScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val store = container.notices
    val state by store.state.collectAsState()
    var url by remember { mutableStateOf(store.downloadUrl) }
    val readOnOpen = remember { store.state.value.readId }   // для пометки «новое» в этот заход

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Notices.sync(store, container.api) } }
            .onSuccess { url = it }
    }
    // Вкладка открыта — всё прочитано (в том числе пришедшее, пока она открыта)
    LaunchedEffect(state.items.firstOrNull()?.id) { store.markRead(store.maxId()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Уведомления", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (state.items.isEmpty()) {
            item { EmptyState("Пока ничего нет", "Здесь появятся сообщения об обновлениях и важные объявления") }
        }
        items(state.items, key = { it.id }) { n ->
            NoticeCard(n, isNew = n.id > readOnOpen, url = url, onDownload = { context.openUrl(url) })
        }
    }
}

@Composable
private fun NoticeCard(n: Notice, isNew: Boolean, url: String, onDownload: () -> Unit) {
    var expanded by rememberSaveable(n.id) { mutableStateOf(false) }
    val isLog = n.kind == NOTICE_CHANGELOG
    val scheme = MaterialTheme.colorScheme

    SiteCard {
        Column(
            modifier = Modifier.clickable { expanded = !expanded }.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isLog) "Обновление" else "Важное",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isLog) scheme.primary else scheme.error,
                )
                if (isNew) Text("новое", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                Spacer(Modifier.weight(1f))
                Text(
                    dateFormat.format(Instant.ofEpochSecond(n.created)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(if (isLog) "Версия ${n.version}" else n.title, style = MaterialTheme.typography.titleMedium)
            if (n.body.isNotBlank()) {
                Text(
                    n.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLog && url.isNotBlank() && Notices.relevant(n)) {
                PrimaryButton("Скачать", onDownload)
            }
        }
    }
}