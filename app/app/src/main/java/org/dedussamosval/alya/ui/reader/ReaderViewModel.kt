package org.dedussamosval.alya.ui.reader

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dedussamosval.alya.AppContainer
import org.dedussamosval.alya.data.Chapter
import org.dedussamosval.alya.data.Keys
import org.dedussamosval.alya.data.WifiOnlyException
import org.dedussamosval.alya.formats.ChapterFiles
import org.dedussamosval.alya.formats.FormatException
import org.dedussamosval.alya.formats.Manifest
import org.dedussamosval.alya.formats.parseImageLine
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface ReaderItem {
    data class Paragraph(val text: String) : ReaderItem

    /** aspect = ширина / высота: заранее известен, поэтому список не «прыгает», пока картинка грузится. */
    data class Picture(val file: File, val aspect: Float, val caption: String) : ReaderItem

    data class Missing(val caption: String) : ReaderItem
    /** Шапка главы: номер главы, название, дата публикации **/
    data class Header(val label: String, val title: String, val date: String?) : ReaderItem
}

sealed interface ReaderState {
    data class Loading(val progress: Float? = null) : ReaderState
    data class Error(val message: String) : ReaderState

    /** [done] = false, пока дочитываются остальные части текста — начало главы уже можно читать. */
    data class Ready(val items: List<ReaderItem>, val isManga: Boolean, val done: Boolean) : ReaderState
}

class ReaderViewModel(private val c: AppContainer, private val chapterId: Int) : ViewModel() {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading())
    val state: StateFlow<ReaderState> = _state

    val chapter: Chapter? = c.catalog.findChapter(chapterId)
    val neighbors: Pair<Chapter?, Chapter?> = chapter?.let { c.catalog.neighbors(it) } ?: (null to null)

    private var deleteOnClose = false

    init {
        load()
    }

    private val dateFormat = DateTimeFormatter
        .ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))
        .withZone(ZoneId.systemDefault())   // перевод в часовой пояс телефона

    private fun headerOf(ch: Chapter) = ReaderItem.Header(
        label = ch.label,
        title = ch.title,
        date = if (ch.created > 0) dateFormat.format(Instant.ofEpochSecond(ch.created)) else null,
    )

    private fun load() {
        val ch = chapter
        if (ch == null) {
            _state.value = ReaderState.Error("Глава не найдена — вернитесь и обновите список")
            return
        }
        viewModelScope.launch {
            try {
                val prefs = c.settings.flow.first()
                deleteOnClose = !prefs.saveOffline && !c.chapters.isDownloaded(ch)
                val file = c.chapters.ensure(ch) { p -> _state.value = ReaderState.Loading(p) }
                c.settings.markRead(ch.id)
                withContext(Dispatchers.IO) {
                    if (ch.fmt == "mtx") loadText(ch, file) else loadPages(ch, file)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WifiOnlyException) {
                _state.value = ReaderState.Error("Включён режим «только по Wi-Fi». Подключитесь к Wi-Fi или отключите режим в настройках.")
            } catch (e: FormatException) {
                _state.value = ReaderState.Error(e.message ?: "Не удалось открыть файл главы")
            } catch (e: IOException) {
                _state.value = ReaderState.Error("Нет соединения с сервером")
            } catch (e: Exception) {
                _state.value = ReaderState.Error("Не удалось открыть главу")
            }
        }
    }

    /** Ранобэ: первая часть текста показывается сразу, остальные дорисовываются по мере чтения. */
    private fun loadText(ch: Chapter, file: File) {
        val manifest = ChapterFiles.readManifest(file)
        val parts = ChapterFiles.mtxPartNames(manifest)
        val items = ArrayList<ReaderItem>()
        items += headerOf(ch)
        val picturesDir = c.files.pagesDirFor(ch)
        parts.forEachIndexed { index, name ->
            items += textItems(file, manifest, ChapterFiles.readText(file, name), picturesDir)
            _state.value = ReaderState.Ready(items.toList(), isManga = false, done = index == parts.lastIndex)
        }
    }

    private fun textItems(file: File, manifest: Manifest, text: String, dir: File): List<ReaderItem> {
        val out = ArrayList<ReaderItem>()
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val image = parseImageLine(line)
            if (image == null) {
                out += ReaderItem.Paragraph(line)
                continue
            }
            out += try {
                val f = ChapterFiles.extractMtxImage(file, manifest, image.number, dir)
                ReaderItem.Picture(f, aspectOf(f), image.caption)
            } catch (e: FormatException) {
                ReaderItem.Missing(image.caption) // битая иллюстрация не ломает всю главу
            }
        }
        return out
    }

    /** Манга: страницы идут сплошной лентой сверху вниз. */
    private fun loadPages(ch: Chapter, file: File) {
        val manifest = ChapterFiles.readManifest(file)
        val pages = ChapterFiles.extractMpxPages(file, manifest, c.files.pagesDirFor(ch))
        val items = listOf<ReaderItem>(headerOf(ch)) + pages.map{ ReaderItem.Picture(it, aspectOf(it), "") }
        _state.value = ReaderState.Ready(items, isManga = true, done = true)
    }

    private fun aspectOf(f: File): Float {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 0.75f
    }

    // ───── оформление текста (общие настройки: те же, что в «Настройки → Оформление») ─────
    fun setFontSize(v: Int) {
        viewModelScope.launch { c.settings.set(Keys.FONT_SIZE, v) }
    }

    fun setFontFamily(v: String) {
        viewModelScope.launch { c.settings.set(Keys.FONT_FAMILY, v) }
    }

    fun setLineSpacing(v: Int) {
        viewModelScope.launch { c.settings.set(Keys.LINE_SPACING, v) }
    }

    override fun onCleared() {
        // Режим «не сохранять прочитанное»: если глава скачана только ради чтения — удаляем её при выходе
        if (deleteOnClose) chapter?.let { c.chapters.delete(it) }
    }
}
