package org.dedussamosval.alya.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class NoticeState(val items: List<Notice> = emptyList(), val readId: Int = 0) {
    /** Непрочитанные; «новая версия», которая уже установлена, не считается. */
    val unread: List<Notice> get() = items.filter { it.id > readId && Notices.relevant(it) }
}

class NoticeStore(private val file: File) {
    private val lock = Any()
    private val _state = MutableStateFlow(NoticeState())
    val state: StateFlow<NoticeState> = _state

    /** false до первой синхронизации: тогда накопившуюся историю молча помечаем прочитанной. */
    @Volatile var initialized = false
        private set

    /** Ссылка на APK с последней синхронизации (в памяти). */
    @Volatile var downloadUrl: String = ""

    init { load() }

    fun maxId(): Int = _state.value.items.maxOfOrNull { it.id } ?: 0

    fun merge(new: List<Notice>, readUpTo: Int? = null) = update { s ->
        // новые записи с сервера перекрывают сохранённые (в push текст обрезан)
        val byId = (s.items + new).associateBy { it.id }
        NoticeState(byId.values.sortedByDescending { it.id }.take(100), maxOf(s.readId, readUpTo ?: 0))
    }

    fun markRead(upTo: Int) = update { s -> s.copy(readId = maxOf(s.readId, upTo)) }

    private fun update(change: (NoticeState) -> NoticeState) {
        synchronized(lock) {
            val next = change(_state.value)
            _state.value = next
            initialized = true
            runCatching { write(next) }
        }
    }

    private fun load() {
        runCatching {
            val o = JSONObject(file.readText())
            val a = o.getJSONArray("items")
            _state.value = NoticeState(
                List(a.length()) { i ->
                    a.getJSONObject(i).let {
                        Notice(it.getInt("id"), it.getString("kind"), it.getString("version"),
                            it.getString("title"), it.getString("body"), it.getLong("created"))
                    }
                },
                o.getInt("read"),
            )
            initialized = true
        }
    }

    private fun write(s: NoticeState) {
        val arr = JSONArray()
        s.items.forEach {
            arr.put(JSONObject().put("id", it.id).put("kind", it.kind).put("version", it.version)
                .put("title", it.title).put("body", it.body).put("created", it.created))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSONObject().put("read", s.readId).put("items", arr).toString())
        tmp.renameTo(file)
    }
}