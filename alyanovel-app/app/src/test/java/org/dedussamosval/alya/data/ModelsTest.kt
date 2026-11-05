package org.dedussamosval.alya.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelsTest {

    private fun fixtureText(name: String): String {
        val url = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "нет фикстуры $name" }
        return File(url.toURI()).readText(Charsets.UTF_8)
    }

    @Test
    fun labels_forEveryChapterType() {
        assertEquals("Пролог", chapterLabel(TYPE_PROLOGUE, 0))
        assertEquals("Эпилог", chapterLabel(TYPE_EPILOGUE, 0))
        assertEquals("Экстра 2", chapterLabel(TYPE_EXTRA, 2))
        assertEquals("Глава 7", chapterLabel(TYPE_CHAPTER, 7))
        assertEquals("Глава 3", chapterLabel("что-то-новое", 3)) // неизвестный тип с будущего сервера — обычная глава
    }

    @Test
    fun parseVolumes_readsType_andOldServersDefaultToChapter() {
        val typed = parseVolumes(KIND_RANOBE, fixtureText("volumes_typed.json")).single().chapters
        assertEquals(listOf(TYPE_PROLOGUE, TYPE_CHAPTER, TYPE_EPILOGUE, TYPE_EXTRA), typed.map { it.type })
        assertEquals(listOf("Пролог", "Глава 1", "Эпилог", "Экстра 1"), typed.map { it.label })

        // ответ старого сервера: поля type нет
        val old = parseVolumes(KIND_RANOBE, fixtureText("volumes.json")).single().chapters.single()
        assertEquals(TYPE_CHAPTER, old.type)
        assertEquals("Глава 1", old.label)
    }

    @Test
    fun ordering_prologue_chapters_epilogue_extras() {
        val chapters = parseVolumes(KIND_RANOBE, fixtureText("volumes_typed.json")).single().chapters
        val shuffled = chapters.reversed()
        val sorted = shuffled.sortedWith(compareBy({ it.volumeNumber }, { typeRank(it.type) }, { it.number }))
        assertEquals(chapters.map { it.id }, sorted.map { it.id })
        assertTrue(typeRank(TYPE_PROLOGUE) < typeRank(TYPE_CHAPTER) && typeRank(TYPE_EPILOGUE) < typeRank(TYPE_EXTRA))
    }
}
