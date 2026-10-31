package org.dedussamosval.alya.formats

import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Контракт форматов описан в docs/PROTOCOL.md (и в common/formats.py на сервере). */
class FormatException(message: String) : Exception(message)

/** Версии, которые умеет читать этот клиент. Файл новее — просим обновить приложение. */
const val MTX_MAX_VERSION = 4
const val MPX_MAX_VERSION = 1

data class Manifest(
    val format: String,          // "mtx" | "mpx"
    val version: Int,
    val title: String,
    val volume: Int,
    val chapter: Int,
    val parts: List<String>,     // mtx: файлы с текстом по порядку (версия 1: пусто — значит один content.txt)
    val images: List<String>,    // mtx v3: иллюстрации, нумерация с 1
    val pages: List<String>,     // mpx: страницы по порядку
    val direction: String,       // mpx: "rtl" | "ltr" | "vertical"
    val type: String,            // "chapter" | "prologue" | "epilogue" | "extra" (нет поля = chapter)
    val code: String,            // R01-P, R01-C03, R01-E, R01-A02 (может быть пустым у старых файлов)
)

/** Иллюстрация в тексте — отдельная строка вида ![подпись](img:N). */
data class ImageLine(val caption: String, val number: Int)

private val IMAGE_LINE = Regex("^!\\[(.*)\\]\\(img:(\\d+)\\)$")

fun parseImageLine(line: String): ImageLine? {
    val m = IMAGE_LINE.matchEntire(line.trim()) ?: return null
    val number = m.groupValues[2].toIntOrNull() ?: return null
    return ImageLine(m.groupValues[1], number)
}

object ChapterFiles {

    /** Открывает ZIP; любой сбой чтения превращает в понятную FormatException (а не ZipException/IOException). */
    private inline fun <T> withZip(file: File, block: (ZipFile) -> T): T {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            throw FormatException("Повреждённый файл: ${e.message}")
        } catch (e: IOException) {
            throw FormatException("Не удалось прочитать файл: ${e.message}")
        }
        return zip.use(block)
    }

    fun readManifest(file: File): Manifest = withZip(file) { readManifest(it) }

    private fun readManifest(zip: ZipFile): Manifest {
        val entry = zip.getEntry("manifest.json") ?: throw FormatException("Повреждённый файл: нет manifest.json")
        val o = try {
            MiniJson.parse(zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)).obj()
        } catch (e: JsonException) {
            throw FormatException("Повреждённый файл: ${e.message}")
        }
        val format = o.string("format")
        val max = when (format) {
            "mtx" -> MTX_MAX_VERSION
            "mpx" -> MPX_MAX_VERSION
            else -> throw FormatException("Неизвестный тип файла")
        }
        val version = o.int("version")
        if (version > max) throw FormatException("Файл создан в более новой версии формата — обновите приложение")
        return Manifest(
            format = format,
            version = version,
            title = o.string("title"),
            volume = o.int("volume"),
            chapter = o.int("chapter"),
            parts = o.list("parts").map { it.toString() },
            images = o.list("images").map { it.toString() },
            pages = o.list("pages").map { it.toString() },
            direction = o.string("direction", "rtl"),
            type = o.string("type", "chapter"),
            code = o.string("code"),
        )
    }

    /** Имена файлов с текстом по порядку (для версии 1 — единственный content.txt). */
    fun mtxPartNames(manifest: Manifest): List<String> {
        if (manifest.format != "mtx") throw FormatException("Это не .mtx")
        return manifest.parts.ifEmpty { listOf("content.txt") }
    }

    /** Читает одну часть текста: части можно показывать по мере чтения — первая появляется сразу. */
    fun readText(file: File, entryName: String): String = withZip(file) { zip ->
        checkName(entryName)
        val entry = zip.getEntry(entryName) ?: throw FormatException("В архиве нет $entryName")
        zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
    }

    /** Достаёт иллюстрацию №number (с 1) из .mtx в outDir. Повторный вызов заново не пишет файл. */
    fun extractMtxImage(file: File, manifest: Manifest, number: Int, outDir: File): File {
        if (number < 1 || number > manifest.images.size) throw FormatException("Нет иллюстрации $number")
        return extractEntry(file, manifest.images[number - 1], outDir)
    }

    /** Распаковывает страницы .mpx в outDir, возвращает файлы по порядку. */
    fun extractMpxPages(file: File, manifest: Manifest, outDir: File): List<File> {
        if (manifest.format != "mpx") throw FormatException("Это не .mpx")
        return manifest.pages.map { extractEntry(file, it, outDir) }
    }

    private fun extractEntry(file: File, entryName: String, outDir: File): File {
        checkName(entryName)
        outDir.mkdirs()
        val dst = File(outDir, File(entryName).name)
        if (!dst.exists()) {
            val tmp = File(outDir, dst.name + ".part")
            withZip(file) { zip ->
                val entry = zip.getEntry(entryName) ?: throw FormatException("В архиве нет $entryName")
                zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
            if (!tmp.renameTo(dst)) throw FormatException("Не удалось сохранить $entryName")
        }
        return dst
    }

    private fun checkName(name: String) {
        if (".." in name || name.startsWith("/") || name.startsWith("\\")) {
            throw FormatException("Недопустимый путь внутри архива")
        }
    }
}
