package dev.continuum.fx.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import dev.continuum.fx.R

/** The Continuum palette. Ink is always pure black; the ground changes with the system theme. */
data class Palette(
    val ground: Color,
    val surface: Color,
    val text: Color,
    val sub: Color,
    val line: Color,
    val ink: Color,
    val onInk: Color,
    val onInkSub: Color,
    val inkTonal: Color,
    val inkRaised: Color,
    val primary: Color,
    val onPrimary: Color,
    val danger: Color,
    val live: Color,
    val onLive: Color,
    val edge: Color,
    val dark: Boolean,
)

val LightPalette = Palette(
    ground = Color(0xFFEEEEEB),
    surface = Color(0xFFE0E0DC),
    text = Color(0xFF121211),
    sub = Color(0xFF5C5C58),
    line = Color(0xFFCFCFCA),
    ink = Color(0xFF000000),
    onInk = Color(0xFFF4F4F1),
    onInkSub = Color(0xFFA3A39E),
    inkTonal = Color(0xFF242423),
    inkRaised = Color(0xFF1C1C1B),
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFF4F4F1),
    danger = Color(0xFFA32D2D),
    live = Color(0xFF97C459),
    onLive = Color(0xFF173404),
    edge = Color.Transparent,
    dark = false,
)

val DarkPalette = LightPalette.copy(
    ground = Color(0xFF161615),
    surface = Color(0xFF262624),
    text = Color(0xFFEDEDEA),
    sub = Color(0xFFA3A39E),
    line = Color(0xFF3A3A37),
    primary = Color(0xFFEDEDEA),
    onPrimary = Color(0xFF121211),
    danger = Color(0xFFF09595),
    edge = Color(0xFF2C2C2A),
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Libron = FontFamily(
    Font(R.font.libron_regular, FontWeight.Normal),
    Font(R.font.libron_bold, FontWeight.Bold),
)

/** Continuum's two springs: Flow for most motion, Snap for presses and small controls. */
object Motion {
    fun <T> flow(): SpringSpec<T> = spring(dampingRatio = 0.613f, stiffness = 240f)
    fun <T> snap(): SpringSpec<T> = spring(dampingRatio = 0.843f, stiffness = 620f)
}

@Composable
fun ContinuumTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette, content = content)
}

@Composable
fun Txt(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Start,
    mono: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontFamily = if (mono) FontFamily.Monospace else Libron,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
