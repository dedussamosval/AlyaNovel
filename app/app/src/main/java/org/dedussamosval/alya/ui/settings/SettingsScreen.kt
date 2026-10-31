package org.dedussamosval.alya.ui.settings

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import org.dedussamosval.alya.BuildConfig
import org.dedussamosval.alya.ui.appViewModel
import org.dedussamosval.alya.ui.components.Pill
import org.dedussamosval.alya.ui.components.PillToggle
import org.dedussamosval.alya.ui.components.SectionTitle
import org.dedussamosval.alya.ui.components.SiteCard
import org.dedussamosval.alya.ui.components.openUrl
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(snackbar: SnackbarHostState) {
    val vm = appViewModel { SettingsViewModel(it) }
    val ui by vm.ui.collectAsState()
    val p = ui.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val serverUrl = BuildConfig.SERVER_URL.trimEnd('/')

    var confirmDeleteDownloads by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSizes() }

    // Android 13+: для уведомлений нужно разрешение. Не дали — выключаем переключатель и подсказываем.
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setNotifyChapters(granted)
        if (!granted) scope.launch { snackbar.showSnackbar("Разрешите уведомления для приложения в настройках системы") }
    }
    fun onNotifyChapters(enabled: Boolean) {
        val needsPermission = enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.setNotifyChapters(enabled)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 4.dp))
        }

        item {
            Group("Оформление") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillToggle(
                        options = listOf("Системная", "Светлая", "Тёмная"),
                        selected = when (p.theme) { "light" -> 1; "dark" -> 2; else -> 0 },
                        onSelect = { vm.setTheme(listOf("system", "light", "dark")[it]) },
                    )
                    var size by remember(p.fontSize) { mutableFloatStateOf(p.fontSize.toFloat()) }
                    Text("Размер текста в читалке: ${size.roundToInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = size,
                        onValueChange = { size = it },
                        onValueChangeFinished = { vm.setFontSize(size.roundToInt()) },
                        valueRange = 14f..26f,
                        steps = 11,
                    )
                    Text(
                        "Так будет выглядеть текст главы.",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = size.sp, lineHeight = (size * 1.6f).sp),
                    )
                }
            }
        }

        item {
            Group("Уведомления") {
                SwitchRow(
                    "Новые главы", "Сообщать о выходе новых глав (проверка примерно раз в 15 минут)",
                    p.notifyChapters, ::onNotifyChapters,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchRow("Сообщения в чате", "Значок непрочитанных на вкладке «Чат»", p.notifyChat, vm::setNotifyChat)
            }
        }

        item {
            Group("Офлайн-чтение") {
                SwitchRow(
                    "Сохранять прочитанные главы", "Иначе глава удаляется после чтения, если не была скачана заранее",
                    p.saveOffline, vm::setSaveOffline,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickRow(
                    "Скачанные главы", "${formatSize(ui.downloadsSize)} · нажмите, чтобы удалить все",
                    onClick = { confirmDeleteDownloads = true },
                )
            }
        }

        item {
            Group("Загрузка") {
                SwitchRow("Только по Wi-Fi", "Не скачивать главы через мобильный интернет", p.wifiOnly, vm::setWifiOnly)
            }
        }

        item {
            Group("Кэш") {
                ClickRow("Очистить кэш страниц", formatSize(ui.cacheSize), onClick = vm::clearCache)
            }
        }

        item {
            Group("О приложении") {
                ClickRow("Версия", BuildConfig.VERSION_NAME, onClick = null)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickRow("Условия использования", null, onClick = { context.openUrl("$serverUrl/tos") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickRow("Политика конфиденциальности", null, onClick = { context.openUrl("$serverUrl/privacy") })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickRow("Проверить обновления", null, onClick = vm::checkUpdates)
            }
        }

        // Подвал, как на сайте: © и кнопки-«пилюли»
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Copyright © 2026 DedusSamosval", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Условия использования", onClick = { context.openUrl("$serverUrl/tos") })
                    Pill("Конфиденциальность", onClick = { context.openUrl("$serverUrl/privacy") })
                }
            }
        }
    }

    if (confirmDeleteDownloads) {
        AlertDialog(
            onDismissRequest = { confirmDeleteDownloads = false },
            title = { Text("Удалить скачанные главы?") },
            text = { Text("Все главы, сохранённые для офлайн-чтения, будут удалены с устройства.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteDownloads = false; vm.clearDownloads() }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDownloads = false }) { Text("Отмена") } },
        )
    }

    ui.update?.let { info ->
        AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            title = { Text("Доступно обновление") },
            text = { Text("Новая версия: ${info.latest}") },
            confirmButton = {
                if (info.url.isNotBlank()) {
                    TextButton(onClick = { vm.dismissUpdate(); context.openUrl(info.url) }) { Text("Скачать") }
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdate) { Text("Позже") } },
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        SiteCard(content = content)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String?, onClick: (() -> Unit)?) {
    val base = Modifier.fillMaxWidth()
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "0 КБ"
}
