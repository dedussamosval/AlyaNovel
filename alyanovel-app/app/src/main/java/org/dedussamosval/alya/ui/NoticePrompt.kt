package org.dedussamosval.alya.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dedussamosval.alya.App
import org.dedussamosval.alya.data.NOTICE_CHANGELOG
import org.dedussamosval.alya.data.Notices
import org.dedussamosval.alya.ui.components.openUrl

/** Диалог с непрочитанными объявлениями. Синхронизация — при каждом выходе приложения на экран. */
@Composable
fun NoticePrompt() {
    val context = LocalContext.current
    val container = (context.applicationContext as App).container
    val store = container.notices
    val state by store.state.collectAsState()
    var url by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { tick++ }
    LaunchedEffect(tick) {
        runCatching { withContext(Dispatchers.IO) { Notices.sync(store, container.api) } }
            .onSuccess { url = it }
    }

    val current = state.unread.minByOrNull { it.id } ?: return
    val isLog = current.kind == NOTICE_CHANGELOG
    val canDownload = isLog && url.isNotBlank()
    fun next() = store.markRead(current.id)      // следующее непрочитанное появится само

    AlertDialog(
        onDismissRequest = { next() },
        title = { Text(if (isLog) "Доступна новая версия ${current.version}" else current.title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(current.body) } },
        confirmButton = {
            if (canDownload) TextButton(onClick = { context.openUrl(url); next() }) { Text("Скачать") }
            else TextButton(onClick = { next() }) { Text("Понятно") }
        },
        dismissButton = if (canDownload) ({ TextButton(onClick = { next() }) { Text("Позже") } }) else null,
    )
}