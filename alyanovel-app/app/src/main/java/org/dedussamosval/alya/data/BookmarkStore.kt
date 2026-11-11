package org.dedussamosval.alya.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Bookmark(val id: String, val chapterId: Int, val itemIndex: Int, val preview: String, val created: Long)

class BookmarkStore(private val file: File) {
    private val lock = Any()
    private val all = MutableStateFlow(load())

    fun forChapter(chapterId: Int): Flow<List<Bookmark>> =
        all.map { l -> l.filter { it.chapterId == chapterId }.sortedBy { it.itemIndex } }.distinctUntilChanged()

    fun add(b: Bookmark) = update { it + b }
    fun remove(id: String) = update { l -> l.filterNot { it.id == id } }

    private fun update(change: (List<Bookmark>) -> List<Bookmark>) = synchronized(lock) {
        val next = change(all.value)
        all.value = next
        runCatching { write(next) }
    }

    private fun load(): List<Bookmark> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { i ->
            a.getJSONObject(i).let {
                Bookmark(it.getString("id"), it.getInt("chapterId"), it.getInt("index"),
                    it.getString("preview"), it.getLong("created"))
            }
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("chapterId", it.chapterId).put("index", it.itemIndex)
                .put("preview", it.preview).put("created", it.created))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}