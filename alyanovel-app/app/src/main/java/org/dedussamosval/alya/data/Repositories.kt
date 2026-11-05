package org.dedussamosval.alya.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class WifiOnlyException : IOException("Включён режим «только по Wi-Fi»")

data class CatalogResult(val volumes: List<Volume>, val offline: Boolean)

class CatalogRepository(private val api: Api, private val files: FileStore) {

    private val memory = ConcurrentHashMap<String, List<Volume>>()

    /** Онлайн — берём с сервера и кэшируем на диск; нет сети — отдаём последний сохранённый список. */
    suspend fun load(kind: String): CatalogResult = withContext(Dispatchers.IO) {
        try {
            val json = api.volumesJson(kind)
            val volumes = parseVolumes(kind, json)
            memory[kind] = volumes
            runCatching { files.catalogFile(kind).writeText(json) }
            files.pruneStale(volumes.flatMap { it.chapters }, kind)
            CatalogResult(volumes, offline = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cached = cached(kind) ?: throw e
            CatalogResult(cached, offline = true)
        }
    }

    /** Последний известный список (память → диск) или null. */
    fun cached(kind: String): List<Volume>? {
        memory[kind]?.let { return it }
        val f = files.catalogFile(kind)
        if (!f.exists()) return null
        return runCatching { parseVolumes(kind, f.readText()) }.getOrNull()?.also { memory[kind] = it }
    }

    fun findChapter(id: Int): Chapter? =
        listOf(KIND_MANGA, KIND_RANOBE).firstNotNullOfOrNull { kind ->
            cached(kind)?.firstNotNullOfOrNull { v -> v.chapters.firstOrNull { it.id == id } }
        }

    /** Соседние главы того же раздела по порядку «том → глава». */
    fun neighbors(ch: Chapter): Pair<Chapter?, Chapter?> {
        val ordered = cached(ch.kind).orEmpty()
            .flatMap { it.chapters }
            .sortedWith(compareBy({ it.volumeNumber }, { typeRank(it.type) }, { it.number }))
        val i = ordered.indexOfFirst { it.id == ch.id }
        if (i < 0) return null to null
        return ordered.getOrNull(i - 1) to ordered.getOrNull(i + 1)
    }
}

class ChapterRepository(
    private val context: Context,
    private val api: Api,
    private val files: FileStore,
    private val settings: SettingsStore,
) {
    private val locks = ConcurrentHashMap<Int, Mutex>()

    fun isDownloaded(ch: Chapter) = files.isDownloaded(ch)

    fun file(ch: Chapter): File = files.chapterFile(ch)

    /** Возвращает локальный файл главы, при необходимости скачивая его. Параллельные вызовы для одной главы не дублируют загрузку. */
    suspend fun ensure(ch: Chapter, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dest = files.chapterFile(ch)
        if (dest.exists()) return@withContext dest
        locks.getOrPut(ch.id) { Mutex() }.withLock {
            if (dest.exists()) return@withLock dest
            if (settings.flow.first().wifiOnly && !Net.isUnmetered(context)) throw WifiOnlyException()
            val tmp = File(dest.path + ".part")
            try {
                api.downloadChapter(ch.id, tmp, onProgress)
                if (!tmp.renameTo(dest)) throw IOException("Не удалось сохранить файл главы")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            dest
        }
    }

    fun delete(ch: Chapter) {
        files.chapterFile(ch).delete()
        files.pagesDirFor(ch).deleteRecursively()
    }
}
