package org.dedussamosval.alya.data

import android.content.Context
import java.io.File

/**
 * filesDir/chapters/<id>_<size>.<fmt>   — скачанные главы (офлайн-чтение; пользователь может удалить в настройках)
 * cacheDir/pages/<id>_<size>/...        — распакованные страницы манги и иллюстрации (система вправе очистить)
 * filesDir/catalog_<kind>.json          — последний полученный список томов (для офлайн-режима)
 * Размер в имени: если админ заменил главу, у неё другой size → скачается заново.
 */
class FileStore(private val context: Context) {

    val chaptersDir: File = File(context.filesDir, "chapters").apply { mkdirs() }
    val pagesDir: File = File(context.cacheDir, "pages").apply { mkdirs() }

    fun chapterFile(ch: Chapter): File = File(chaptersDir, "${ch.id}_${ch.size}.${ch.fmt}")

    fun pagesDirFor(ch: Chapter): File = File(pagesDir, "${ch.id}_${ch.size}")

    fun catalogFile(kind: String): File = File(context.filesDir, "catalog_$kind.json")

    fun isDownloaded(ch: Chapter): Boolean = chapterFile(ch).exists()

    fun downloadsSize(): Long = sizeOf(chaptersDir)

    fun cacheSize(): Long = sizeOf(pagesDir)

    fun clearCache() {
        pagesDir.deleteRecursively()
        pagesDir.mkdirs()
    }

    fun clearDownloads() {
        chaptersDir.deleteRecursively()
        chaptersDir.mkdirs()
    }

    /** Удаляет файлы глав, которые админ заменил (тот же id, другой size) или удалил с сервера. */
    fun pruneStale(current: List<Chapter>, kind: String) {
        val known = current.associateBy { it.id }
        chaptersDir.listFiles()?.forEach { f ->
            val stem = f.nameWithoutExtension
            val id = stem.substringBefore('_').toIntOrNull() ?: return@forEach
            val size = stem.substringAfter('_', "").toLongOrNull() ?: return@forEach
            val ch = known[id] ?: return@forEach   // глава из другого раздела или не найдена — не трогаем
            if (ch.kind == kind && ch.size != size) {
                f.delete()
                File(pagesDir, stem).deleteRecursively()
            }
        }
    }

    private fun sizeOf(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
