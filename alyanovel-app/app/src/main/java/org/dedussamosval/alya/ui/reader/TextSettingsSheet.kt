package org.dedussamosval.alya.ui.reader

import androidx.compose.ui.text.font.FontFamily


fun fontFamilyOf(key: String): FontFamily = when (key) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

// Свой шрифт: положить .ttf в res/font/ и добавить ветку FontFamily(Font(R.font.xxx)) - на будущее