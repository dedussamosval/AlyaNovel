package org.dedussamosval.alya.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dedussamosval.alya.AppContainer
import org.dedussamosval.alya.data.AuthPoll
import org.dedussamosval.alya.data.ChatClient
import org.dedussamosval.alya.data.ChatEvent
import org.dedussamosval.alya.data.ChatMessage
import org.dedussamosval.alya.data.UnauthorizedException
import java.io.IOException
import kotlin.coroutines.coroutineContext

sealed interface LoginState {
    data object Idle : LoginState
    data object Waiting : LoginState
    data class Error(val message: String) : LoginState
}

data class ChatUi(
    val loggedIn: Boolean = false,
    val userName: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val connected: Boolean = false,
    val login: LoginState = LoginState.Idle,
    val unread: Int = 0,
)

class ChatViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ChatUi())
    val state: StateFlow<ChatUi> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val client = ChatClient(c.api)
    private val foreground = MutableStateFlow(false)

    @Volatile private var chatTabVisible = false
    @Volatile private var countUnread = true
    private var loginJob: Job? = null

    init {
        // Настройки → «вошёл ли», имя, нужен ли значок непрочитанных
        viewModelScope.launch {
            c.settings.flow.collect { p ->
                countUnread = p.notifyChat
                _state.update {
                    it.copy(
                        loggedIn = p.session.isNotEmpty(),
                        userName = p.userName,
                        messages = if (p.session.isEmpty()) emptyList() else it.messages,
                    )
                }
            }
        }
        // Соединение держим, только пока есть сессия и приложение на экране
        viewModelScope.launch {
            combine(c.settings.flow.map { it.session }.distinctUntilChanged(), foreground) { session, fg ->
                if (fg) session else ""
            }.distinctUntilChanged().collectLatest { session ->
                if (session.isNotEmpty()) runChat(session) else _state.update { it.copy(connected = false) }
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun setChatTabVisible(visible: Boolean) {
        chatTabVisible = visible
        if (visible) _state.update { it.copy(unread = 0) }
    }

    // ───── соединение ─────
    private suspend fun runChat(session: String) {
        try {
            val history = withContext(Dispatchers.IO) { c.api.chatHistory(session) }
            _state.update { s -> s.copy(messages = merge(s.messages, history)) }
        } catch (e: UnauthorizedException) {
            logout()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // нет сети: историю подтянем при следующем подключении
        }

        var backoff = 2_000L
        while (coroutineContext.isActive) {
            var unauthorized = false
            try {
                client.events(session).collect { event ->
                    when (event) {
                        ChatEvent.Connected -> {
                            backoff = 2_000L
                            _state.update { it.copy(connected = true) }
                        }
                        ChatEvent.Unauthorized -> {
                            unauthorized = true
                        }
                        is ChatEvent.Message -> addMessage(event.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // оборвалось — уйдём на переподключение
            }
            _state.update { it.copy(connected = false) }
            if (unauthorized) {
                logout()
                return
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun merge(old: List<ChatMessage>, new: List<ChatMessage>): List<ChatMessage> =
        (old + new).distinctBy { it.id }.sortedBy { it.id }

    private fun addMessage(m: ChatMessage) {
        _state.update { s ->
            if (s.messages.any { it.id == m.id }) {
                s
            } else {
                val foreign = m.name != s.userName
                s.copy(
                    messages = s.messages + m,
                    unread = if (!chatTabVisible && foreign && countUnread) s.unread + 1 else s.unread,
                )
            }
        }
    }

    /** true — сообщение ушло в сокет (поле ввода можно очищать). */
    fun send(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val ok = client.send(t)
        if (!ok) _messages.tryEmit("Нет соединения с чатом")
        return ok
    }

    // ───── вход через Telegram ─────
    /** Открывает бота в Telegram (openUrl) и опрашивает сервер, пока вы не нажмёте Start. */
    fun startLogin(openUrl: (String) -> Unit) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _state.update { it.copy(login = LoginState.Waiting) }
            try {
                val start = withContext(Dispatchers.IO) { c.api.authStart() }
                openUrl(start.url)
                val deadline = System.currentTimeMillis() + 10 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(2_000)
                    val result = try {
                        withContext(Dispatchers.IO) { c.api.authPoll(start.token) }
                    } catch (e: IOException) {
                        AuthPoll.Pending // секундный обрыв сети не должен срывать вход
                    }
                    when (result) {
                        is AuthPoll.Ok -> {
                            c.settings.saveSession(result.session, result.name)
                            _state.update { it.copy(login = LoginState.Idle) }
                            return@launch
                        }
                        AuthPoll.Expired -> {
                            _state.update { it.copy(login = LoginState.Error("Ссылка устарела. Попробуйте ещё раз")) }
                            return@launch
                        }
                        AuthPoll.Pending -> Unit
                    }
                }
                _state.update { it.copy(login = LoginState.Error("Время ожидания вышло. Попробуйте ещё раз")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(login = LoginState.Error("Нет связи с сервером")) }
            }
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        _state.update { it.copy(login = LoginState.Idle) }
    }

    fun logout() {
        viewModelScope.launch { c.settings.clearSession() }
    }
}
