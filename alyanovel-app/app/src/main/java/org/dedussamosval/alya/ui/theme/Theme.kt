package org.dedussamosval.alya.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Палитра один в один с сайтом (server/static/base.css):
 *   фон #f6f7fb / #14151c, карточка #fff / #1d1f29, текст #1b1d27 / #eceef6,
 *   приглушённый #6b7080 / #9aa0b4, акцент #5b5bf0, рамка #e3e5ee / #2c2f3d, ошибка #d64545.
 * Роли Material: background = фон, surface = карточка, onSurfaceVariant = приглушённый текст,
 * outlineVariant = рамка, primary = акцент.
 */
private val Accent = Color(0xFF5B5BF0)
private val Danger = Color(0xFFD64545)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4FD),
    onPrimaryContainer = Color(0xFF1C1C6E),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4FD),
    onSecondaryContainer = Color(0xFF1C1C6E),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1B1D27),
    surface = Color.White,
    onSurface = Color(0xFF1B1D27),
    surfaceVariant = Color(0xFFEDEEF5),
    onSurfaceVariant = Color(0xFF6B7080),
    outline = Color(0xFFC9CCDA),
    outlineVariant = Color(0xFFE3E5EE),
    error = Danger,
    onError = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDEEF5),
    surfaceContainerHighest = Color(0xFFE3E5EE),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E2F66),
    onPrimaryContainer = Color(0xFFDCDCFF),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2E2F66),
    onSecondaryContainer = Color(0xFFDCDCFF),
    background = Color(0xFF14151C),
    onBackground = Color(0xFFECEEF6),
    surface = Color(0xFF1D1F29),
    onSurface = Color(0xFFECEEF6),
    surfaceVariant = Color(0xFF262833),
    onSurfaceVariant = Color(0xFF9AA0B4),
    outline = Color(0xFF3A3D4D),
    outlineVariant = Color(0xFF2C2F3D),
    error = Danger,
    onError = Color.White,
    surfaceContainerLowest = Color(0xFF14151C),
    surfaceContainerLow = Color(0xFF1D1F29),
    surfaceContainer = Color(0xFF1D1F29),
    surfaceContainerHigh = Color(0xFF262833),
    surfaceContainerHighest = Color(0xFF2C2F3D),
)

// Радиусы как на сайте: карточки 14dp, hero 20dp, «пилюли» — полностью круглые.
private val SiteShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val SiteTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun AlyaNovelTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = SiteShapes,
        typography = SiteTypography,
        content = content,
    )
}
