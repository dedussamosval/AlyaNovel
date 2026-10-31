package org.dedussamosval.alya.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Фикстуры в src/test/resources/fixtures сделаны настоящим Python-кодом сервера (common/formats.py). */
class FormatsTest {

    private fun fixture(name: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) { "нет фикстуры $name" }
        return File(url.toURI())
    }

    private fun expected(name: String) = fixture(name).readText(Charsets.UTF_8)

    private fun tmpDir(): File = Files.createTempDirectory("mf").toFile()

    private fun fullText(file: File): String {
        val m = ChapterFiles.readManifest(file)
        return ChapterFiles.mtxPartNames(m).joinToString("") { ChapterFiles.readText(file, it) }
    }

    private fun expectFormatError(block: () -> Unit): FormatException {
        try {
            block()
        } catch (e: FormatException) {
            return e
        }
        fail("ожидалась FormatException")
        throw IllegalStateException()
    }

    @Test
    fun mtxParts_joinBackToOriginal() {
        val file = fixture("v2_parts.mtx")
        val m = ChapterFiles.readManifest(file)
        assertEquals("mtx", m.format)
        assertEquals(2, m.version) // файл старой версии 2 — читается как раньше
        assertEquals("Пролог «тест»", m.title)
        assertEquals(2, m.volume)
        assertEquals(7, m.chapter)
        assertTrue("ожидалось много частей, а их ${m.parts.size}", m.parts.size > 5)
        assertEquals("0001.txt", m.parts[0])
        assertEquals("0002.txt", m.parts[1])
        assertEquals(expected("v2_parts.expected.txt"), fullText(file))
    }

    @Test
    fun mtxV3_textOnly_hasNoImages() {
        // Версия 3 — единственная актуальная: глава может быть просто текстом, тогда images пуст
        val file = fixture("v3_text_only.mtx")
        val m = ChapterFiles.readManifest(file)
        assertEquals(3, m.version)
        assertEquals("Просто текст", m.title)
        assertEquals(3, m.volume)
        assertEquals(4, m.chapter)
        assertTrue(m.images.isEmpty())
        assertEquals(listOf("0001.txt"), m.parts)
        assertEquals(expected("v3_text_only.expected.txt"), fullText(file))
        expectFormatError { ChapterFiles.extractMtxImage(file, m, 1, tmpDir()) }
    }

    @Test
    fun chapterTypes_prologueExtraAndLegacyDefaults() {
        val prologue = ChapterFiles.readManifest(fixture("v3_prologue.mtx"))
        assertEquals("prologue", prologue.type)
        assertEquals("R01-P", prologue.code)
        assertEquals(0, prologue.chapter)

        val extra = ChapterFiles.readManifest(fixture("v3_extra.mtx"))
        assertEquals("extra", extra.type)
        assertEquals("R01-A02", extra.code)
        assertEquals(2, extra.chapter)

        val mpx = ChapterFiles.readManifest(fixture("prologue.mpx")) // манга: тот же манифестный контракт
        assertEquals("prologue", mpx.type)
        assertEquals("M02-P", mpx.code)

        // файлы до появления типов: type по умолчанию chapter, кода нет
        val legacy = ChapterFiles.readManifest(fixture("v1_legacy.mtx"))
        assertEquals("chapter", legacy.type)
        assertEquals("", legacy.code)
    }

    @Test
    fun mtxImages_extractedAndMarkersParsed() {
        val file = fixture("v3_images.mtx")
        val m = ChapterFiles.readManifest(file)
        assertEquals(3, m.version)
        assertEquals(2, m.images.size)
        val text = fullText(file)
        assertEquals(expected("v3_images.expected.txt"), text)

        val lines = text.split("\n")
        assertEquals(ImageLine("Карта мира", 1), parseImageLine(lines[1]))
        assertEquals(ImageLine("", 2), parseImageLine(lines[3]))
        assertNull(parseImageLine(lines[0]))
        assertNull(parseImageLine("текст ![](img:1)"))

        val dir = tmpDir()
        val img1 = ChapterFiles.extractMtxImage(file, m, 1, dir)
        assertTrue(img1.exists() && img1.length() > 0)
        assertTrue(img1.name.endsWith(".png"))
        assertEquals(img1, ChapterFiles.extractMtxImage(file, m, 1, dir)) // повторный вызов
        expectFormatError { ChapterFiles.extractMtxImage(file, m, 3, dir) }
        expectFormatError { ChapterFiles.extractMtxImage(file, m, 0, dir) }
    }

    @Test
    fun legacyMtx_singleContentFile() {
        val file = fixture("v1_legacy.mtx")
        val m = ChapterFiles.readManifest(file)
        assertEquals(1, m.version)
        assertEquals(listOf("content.txt"), ChapterFiles.mtxPartNames(m))
        assertEquals("старый формат\nвторая строка", fullText(file))
    }

    @Test
    fun mpx_pagesExtractedInOrder() {
        val file = fixture("pages.mpx")
        val m = ChapterFiles.readManifest(file)
        assertEquals("mpx", m.format)
        assertEquals("ltr", m.direction)
        assertEquals(3, m.pages.size)
        val pages = ChapterFiles.extractMpxPages(file, m, tmpDir())
        assertEquals(listOf("0001.png", "0002.png", "0003.png"), pages.map { it.name })
        assertTrue(pages.all { it.length() > 0 })
        expectFormatError { ChapterFiles.mtxPartNames(m) }
    }

    @Test
    fun newerVersion_isRejectedWithHint() {
        val e = expectFormatError { ChapterFiles.readManifest(fixture("newer.mtx")) }
        assertTrue(e.message!!.contains("обновите приложение"))
    }

    @Test
    fun brokenFile_isFormatError() {
        val bad = File.createTempFile("bad", ".mtx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        expectFormatError { ChapterFiles.readManifest(bad) }
    }

    @Test
    fun json_parsesServerResponses_asciiAndUtf8() {
        for (name in listOf("volumes.json", "volumes.utf8.json")) {
            val vols = MiniJson.parse(expected(name)).arr()
            val v = vols[0].obj()
            assertEquals("Начало «1»", v.string("title"))
            assertEquals(1, v.int("id"))
            val ch = v.list("chapters")[0].obj()
            assertEquals("mtx", ch.string("fmt"))
            assertEquals(359L, ch.long("size"))
            assertEquals(1792712832L, ch.long("created"))
        }
    }

    @Test
    fun json_edgeCases() {
        assertEquals("a\"b\\c/d\n\t😀", MiniJson.parse("\"a\\\"b\\\\c\\/d\\n\\t\\ud83d\\ude00\""))
        assertEquals(-12L, MiniJson.parse("-12"))
        assertEquals(1.5e3, MiniJson.parse("1.5e3"))
        assertEquals(true, MiniJson.parse(" true "))
        assertNull(MiniJson.parse("null"))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[ ]"))
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{ }"))
        assertEquals(mapOf("a" to listOf(1L, 2L), "b" to mapOf("c" to null)), MiniJson.parse("""{"a":[1,2],"b":{"c":null}}"""))
        for (bad in listOf("", "{", "[1,", "{\"a\" 1}", "\"abc", "tru", "[1] x", "{\"a\":}")) {
            try {
                MiniJson.parse(bad)
                fail("должно падать: $bad")
            } catch (_: JsonException) {
            }
        }
        assertNotNull(MiniJson.parse("{}"))
    }
}
