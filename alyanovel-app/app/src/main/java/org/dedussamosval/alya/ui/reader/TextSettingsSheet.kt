package org.dedussamosval.alya.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dedussamosval.alya.data.Prefs
import org.dedussamosval.alya.ui.components.PillToggle
import kotlin.math.roundToInt

private val FAMILY_KEYS = listOf("sans", "serif", "mono")
private val FAMILY_LABELS = listOf("Без засечек", "С засечками", "Моно")

/**
 * Ключ из настроек → шрифт. Здесь системные шрифты (ничего не добавляют к размеру APK).
 * Свой шрифт: положите .ttf в app/src/main/res/font/ (имя — строчными буквами, например literata.ttf) и добавьте ветку:
 *   "literata" -> FontFamily(Font(R.font.literata))
 * плюс пункт в FAMILY_KEYS / FAMILY_LABELS выше.
 */
fun fontFamilyOf(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

/** Панель «Aa» внутри читалки: шрифт, размер, межстрочный интервал. Размер и интервал показываются на образце сразу, сохраняются при отпускании ползунка. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSettingsSheet(
    prefs: Prefs,
    onFontFamily: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineSpacing: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        var size by remember(prefs.fontSize) { mutableFloatStateOf(prefs.fontSize.toFloat()) }
        var spacing by remember(prefs.lineSpacing) { mutableFloatStateOf(prefs.lineSpacing.toFloat()) }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Текст", style = MaterialTheme.typography.titleLarge)

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
                onValueChangeFinished = { onLineSpacing(((spacing / 10f).roundToInt() * 10)) },
                valueRange = 120f..200f,
                steps = 7,
            )

            Text(
                "Так будет выглядеть текст главы: съешь же ещё этих мягких французских булок, да выпей чаю.",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = size.sp,
                    lineHeight = (size * spacing / 100f).sp,
                    fontFamily = fontFamilyOf(prefs.fontFamily),
                ),
            )
        }
    }
}
