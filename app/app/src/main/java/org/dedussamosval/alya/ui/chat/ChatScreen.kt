package org.dedussamosval.alya.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dedussamosval.alya.data.ChatMessage
import org.dedussamosval.alya.ui.components.PrimaryButton
import org.dedussamosval.alya.ui.components.SiteCard
import org.dedussamosval.alya.ui.components.openUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(vm: ChatViewModel, snackbar: SnackbarHostState) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    if (!ui.loggedIn) {
        LoginPrompt(
            login = ui.login,
            onLogin = { vm.startLogin { url -> context.openUrl(url) } },
            onCancel = { vm.cancelLogin() },
        )
    } else {
        ChatContent(ui = ui, onSend = { vm.send(it) }, onLogout = { vm.logout() })
    }
}

@Composable
private fun LoginPrompt(login: LoginState, onLogin: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        SiteCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
                Text("Чат читателей", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Чат доступен после входа через Telegram — без паролей: откроется бот, нажмите Start и вернитесь в приложение. (Временно недоступно)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                when (login) {
                    LoginState.Idle -> PrimaryButton("Войти через Telegram", onLogin, enabled = false) // временно отключил кнопку
                    LoginState.Waiting -> {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text(
                            "Ждём подтверждения в Telegram…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancel) { Text("Отмена") }
                    }
                    is LoginState.Error -> {
                        Text(login.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        PrimaryButton("Попробовать снова", onLogin)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatContent(ui: ChatUi, onSend: (String) -> Boolean, onLogout: () -> Unit) {
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Чат", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = if (ui.connected) ui.userName else "${ui.userName} · подключение…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Выйти из аккаунта")
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.messages, key = { it.id }) { m -> Bubble(m, mine = m.name == ui.userName) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (onSend(text)) text = "" }),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { if (onSend(text)) text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить")
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomEnd = if (mine) 4.dp else 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp,
            ),
            color = if (mine) scheme.primary else scheme.surface,
            border = if (mine) null else BorderStroke(1.dp, scheme.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (!mine) {
                    Text(m.name, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                }
                Text(m.text, color = if (mine) scheme.onPrimary else scheme.onSurface)
                Text(
                    text = formatTime(m.created),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mine) scheme.onPrimary.copy(alpha = 0.7f) else scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

private fun formatTime(epochSeconds: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
