package org.dedussamosval.alya.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface ChatEvent {
    data object Connected : ChatEvent
    data object Unauthorized : ChatEvent
    data class Message(val message: ChatMessage) : ChatEvent
}

/**
 * Одно соединение с чатом. [events] завершается, когда соединение закрылось/оборвалось —
 * переподключением (с паузой) занимается вызывающий код (ChatViewModel).
 */
class ChatClient(private val api: Api) {

    @Volatile
    private var socket: WebSocket? = null

    fun send(text: String): Boolean = socket?.send(text) ?: false

    fun events(session: String): Flow<ChatEvent> = callbackFlow {
        val ws = api.openChat(session, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(ChatEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { parseChatMessage(text) }.getOrNull()?.let { trySend(ChatEvent.Message(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4401) trySend(ChatEvent.Unauthorized)
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Сервер закрывает рукопожатие кодом 403/401, если сессия недействительна
                if (response?.code == 401 || response?.code == 403) trySend(ChatEvent.Unauthorized)
                close()
            }
        })
        socket = ws
        awaitClose {
            if (socket === ws) socket = null
            ws.cancel()
        }
    }
}
