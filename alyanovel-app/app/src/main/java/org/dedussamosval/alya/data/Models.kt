package org.dedussamosval.alya.data

import org.dedussamosval.alya.formats.MiniJson
import org.dedussamosval.alya.formats.arr
import org.dedussamosval.alya.formats.int
import org.dedussamosval.alya.formats.list
import org.dedussamosval.alya.formats.long
import org.dedussamosval.alya.formats.obj
import org.dedussamosval.alya.formats.string

const val KIND_MANGA = "manga"
const val KIND_RANOBE = "ranobe"

// Тип главы внутри тома (поле type в API и манифесте). Нет поля = обычная глава (старые сервера и файлы).
const val TYPE_CHAPTER = "chapter"
const val TYPE_PROLOGUE = "prologue"   // R01-P, номера нет
const val TYPE_EPILOGUE = "epilogue"   // R01-E, номера нет
const val TYPE_EXTRA = "extra"         // R01-A01… дополнительные главы вне рамок сюжета
const val NOTICE_CHANGELOG = "changelog"
const val NOTICE_IMPORTANT = "important"

/** Порядок внутри тома: пролог → главы → эпилог → экстра (так же сортирует сервер). */
fun typeRank(type: String): Int = when (type) {
    TYPE_PROLOGUE -> 0
    TYPE_EPILOGUE -> 2
    TYPE_EXTRA -> 3
    else -> 1
}

/** Подпись для списков, шапки читалки и уведомлений. */
fun chapterLabel(type: String, number: Int): String = when (type) {
    TYPE_PROLOGUE -> "Пролог"
    TYPE_EPILOGUE -> "Эпилог"
    TYPE_EXTRA -> "Экстра $number"
    else -> "Глава $number"
}

data class Chapter(
    val id: Int,
    val volumeId: Int,
    val volumeNumber: Int,
    val kind: String,        // "manga" | "ranobe"
    val number: Int,
    val title: String,
    val fmt: String,         // "mtx" | "mpx"
    val size: Long,          // размер файла: вместе с id определяет имя файла на диске (замена главы = новый файл)
    val created: Long,       // unix-секунды
    val type: String = TYPE_CHAPTER,
) {
    val label: String get() = chapterLabel(type, number)
}

data class Volume(
    val id: Int,
    val kind: String,
    val number: Int,
    val title: String,
    val chapters: List<Chapter>,
)

data class ChatMessage(val id: Long, val name: String, val text: String, val created: Long)

data class AppVersion(val latest: String, val url: String)

data class AuthStart(val token: String, val url: String)

sealed interface AuthPoll {
    data object Pending : AuthPoll
    data object Expired : AuthPoll
    data class Ok(val session: String, val name: String) : AuthPoll
}

data class LatestChapter(
    val id: Int, val kind: String, val volume: Int, val number: Int, val title: String, val created: Long,
    val type: String = TYPE_CHAPTER,
)

data class Notice(val id: Int, val kind: String, val version: String, val title: String, val body: String, val created: Long)
data class NoticeBatch(val top: Int, val url: String, val items: List<Notice>)

fun parseVolumes(kind: String, json: String): List<Volume> =
    MiniJson.parse(json).arr().map { v ->
        val o = v.obj()
        val volumeId = o.int("id")
        val volumeNumber = o.int("number")
        Volume(
            id = volumeId,
            kind = kind,
            number = volumeNumber,
            title = o.string("title"),
            chapters = o.list("chapters").map { c ->
                val co = c.obj()
                Chapter(
                    id = co.int("id"),
                    volumeId = volumeId,
                    volumeNumber = volumeNumber,
                    kind = kind,
                    number = co.int("number"),
                    title = co.string("title"),
                    fmt = co.string("fmt"),
                    size = co.long("size"),
                    created = co.long("created"),
                    type = co.string("type", TYPE_CHAPTER),
                )
            },
        )
    }

fun parseChatMessage(json: String): ChatMessage = parseChatMessage(MiniJson.parse(json).obj())

fun parseChatMessage(o: Map<String, Any?>): ChatMessage =
    ChatMessage(id = o.long("id"), name = o.string("name"), text = o.string("text"), created = o.long("created"))

fun parseChatHistory(json: String): List<ChatMessage> = MiniJson.parse(json).arr().map { parseChatMessage(it.obj()) }

fun parseLatest(json: String): List<LatestChapter> = MiniJson.parse(json).arr().map {
    val o = it.obj()
    LatestChapter(o.int("id"), o.string("kind"), o.int("volume"), o.int("number"), o.string("title"), o.long("created"),
        o.string("type", TYPE_CHAPTER))
}

fun parseNotices(json: String): NoticeBatch {
    val o = MiniJson.parse(json).obj()
    return NoticeBatch(
        top = o.int("top"),
        url = o.string("url"),
        items = o.list("items").map {
            val n = it.obj()
            Notice(n.int("id"), n.string("kind"), n.string("version"), n.string("title"), n.string("body"), n.long("created"))
        },
    )
}
