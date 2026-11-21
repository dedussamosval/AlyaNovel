package org.dedussamosval.alya.ui.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.dedussamosval.alya.data.MANGA_PAGER_H
import org.dedussamosval.alya.data.MANGA_PAGER_V
import org.dedussamosval.alya.data.MANGA_STRIP
import org.dedussamosval.alya.data.Prefs
import org.dedussamosval.alya.ui.components.PillToggle
import kotlin.math.roundToInt

private val FAMILY_KEYS = listOf("sans", "serif", "mono")
private val FAMILY_LABELS = listOf("Без засечек", "С засечками", "Моно")
private val MODE_KEYS = listOf(MANGA_STRIP, MANGA_PAGER_H, MANGA_PAGER_V)
private val MODE_LABELS = listOf("Лентой", "По одной →", "По одной ↓")

// Подсвечивает все вхождения query в тексте (без учёта регистра)
fun highlightMatches(text: String, query: String, color: Color): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val i = text.indexOf(query, from, ignoreCase = true)
            if (i < 0) break
            addStyle(SpanStyle(background = color), i, i + query.length)
            from = i + query.length
        }
    }
}

/**
 * Закрывает панель по тапу вне её. Тапы, прокрутку и зум не перехватывает
 */
fun Modifier.dismissOnOutsideTap(
    active: Boolean,
    isInside: (Offset) -> Boolean,
    onDismiss: () -> Unit,
): Modifier = if (!active) this else pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (isInside(down.position)) return@awaitEachGesture   // тап по самой панели — не закрываем

        var tap = true
        var lastTime = down.uptimeMillis
        do {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            lastTime = e.changes.maxOf { it.uptimeMillis }
            if (e.changes.size > 1) tap = false                       // второй палец — это зум
            val c = e.changes.firstOrNull { it.id == down.id } ?: break
            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) tap = false  // скролл
        } while (e.changes.any { it.pressed })

        if (tap && lastTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) onDismiss()
    }
}

/**
 * Плавающая панель настроек читалки ("облако" снизу, без затемнения — текст виден и меняется сразу).
 * tab: 0 — оформление текста, 1 — поиск (только для ранобэ).
 */
@Composable
fun ReaderMenuPanel(
    isManga: Boolean,
    loading: Boolean,
    prefs: Prefs,
    tab: Int,
    onTab: (Int) -> Unit,
    onFontFamily: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineSpacing: (Int) -> Unit,
    onMangaMode: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    onStep: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(12.dp).fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isManga) {
                    Text("Режим чтения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                } else {
                    PillToggle(listOf("Текст", "Поиск"), tab, onTab, Modifier.weight(1f))
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Закрыть") }
            }
            when {
                isManga -> MangaModeContent(prefs.mangaMode, onMangaMode)
                tab == 0 -> TextContent(prefs, onFontFamily, onFontSize, onLineSpacing)
                else -> SearchContent(query, onQuery, matchCount, matchPos, loading, onStep)
            }
        }
    }
}

@Composable
private fun MangaModeContent(mode: String, onMode: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PillToggle(
            options = MODE_LABELS,
            selected = MODE_KEYS.indexOf(mode).coerceAtLeast(0),
            onSelect = { onMode(MODE_KEYS[it]) },
        )
        Text(
            when (mode) {
                MANGA_PAGER_H -> "Страницы листаются свайпом влево и вправо, по одной на экран."
                MANGA_PAGER_V -> "Страницы листаются свайпом вверх и вниз, по одной на экран."
                else -> "Все страницы идут сплошной лентой."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TextContent(
    prefs: Prefs,
    onFontFamily: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineSpacing: (Int) -> Unit,
) {
    var size by remember(prefs.fontSize) { mutableFloatStateOf(prefs.fontSize.toFloat()) }
    var spacing by remember(prefs.lineSpacing) { mutableFloatStateOf(prefs.lineSpacing.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PillToggle(
            options = FAMILY_LABELS,
            selected = FAMILY_KEYS.indexOf(prefs.fontFamily).coerceAtLeast(0),
            onSelect = { onFontFamily(FAMILY_KEYS[it]) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Размер: ${size.roundToInt()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { size = (size - 1).coerceAtLeast(14f); onFontSize(size.roundToInt()) }) {
                Icon(Icons.Rounded.Remove, contentDescription = "Меньше")
            }
            IconButton(onClick = { size = (size + 1).coerceAtMost(30f); onFontSize(size.roundToInt()) }) {
                Icon(Icons.Rounded.Add, contentDescription = "Больше")
            }
        }
        Slider(
            value = size,
            onValueChange = { size = it },
            onValueChangeFinished = { onFontSize(size.roundToInt()) },
            valueRange = 14f..30f,
            steps = 15,
        )
        Text("Межстрочный интервал: ${"%.1f".format(spacing / 100f)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = spacing,
            onValueChange = { spacing = it },
            onValueChangeFinished = { onLineSpacing((spacing / 10f).roundToInt() * 10) },
            valueRange = 120f..200f,
            steps = 7,
        )
    }
}

@Composable
private fun SearchContent(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    loading: Boolean,
    onStep: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val q = query.trim()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Поиск по тексту главы") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Clear, contentDescription = "Очистить") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onStep(1) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    q.length < 2 -> "Введите хотя бы 2 символа"
                    matchCount == 0 ->
                        if (loading) "Пока не найдено — глава ещё догружается" else "Ничего не найдено"
                    else -> "Абзац ${matchPos + 1} из $matchCount"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onStep(-1) }, enabled = matchCount > 0) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Предыдущее совпадение")
            }
            IconButton(onClick = { onStep(1) }, enabled = matchCount > 0) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Следующее совпадение")
            }
        }
    }
}