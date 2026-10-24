/*
 * Continuum Keyboard: a modified version of FUTO Keyboard. This file is new in the
 * modified version and is distributed under the same FUTO Source First License 1.1-kb.
 */
package org.futo.inputmethod.latin.uix.theme.presets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.core.content.res.ResourcesCompat
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.KeyboardColorScheme
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.extendedLightColorScheme
import org.futo.inputmethod.latin.uix.theme.ThemeOption

/*
 * The Continuum design language: flat surfaces, pure black ink, Libron type and fully
 * rounded keys. Ink is the signature look; Light keeps the same shapes on paper tones.
 */

private val continuumInk = extendedDarkColorScheme(
    primary = Color(0xFFF4F4F1),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF242423),
    onPrimaryContainer = Color(0xFFF4F4F1),
    secondary = Color(0xFFF4F4F1),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF242423),
    onSecondaryContainer = Color(0xFFF4F4F1),
    tertiary = Color(0xFF97C459),
    onTertiary = Color(0xFF173404),
    tertiaryContainer = Color(0xFF242423),
    onTertiaryContainer = Color(0xFF97C459),
    error = Color(0xFFF09595),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A1414),
    onErrorContainer = Color(0xFFF09595),
    outline = Color(0xFF3A3A38),
    outlineVariant = Color(0xFF242423),
    surface = Color(0xFF161615),
    onSurface = Color(0xFFF4F4F1),
    onSurfaceVariant = Color(0xFFA3A39E),
    surfaceContainerHighest = Color(0xFF262624),
    shadow = Color(0x00000000),
    keyboardSurface = Color(0xFF000000),
    keyboardSurfaceDim = Color(0xFF000000),
    keyboardContainer = Color(0xFF2A2A28),
    keyboardContainerVariant = Color(0xFF1C1C1B),
    onKeyboardContainer = Color(0xFFF4F4F1),
    keyboardPress = Color(0xFF3A3A38),
    keyboardFade0 = Color(0xFF000000),
    keyboardFade1 = Color(0xFF000000),
    primaryTransparent = Color(0xFFF4F4F1).copy(alpha = 0.22f),
    onSurfaceTransparent = Color(0xFFF4F4F1).copy(alpha = 0.1f),
    navigationBarColor = Color(0xFF000000),
    keyboardContainerPressed = Color(0xFF3A3A38),
    onKeyboardContainerPressed = Color(0xFFF4F4F1),
    hintColor = Color(0xFF8A8A85),
)

private val continuumLight = extendedLightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFF4F4F1),
    primaryContainer = Color(0xFFE0E0DC),
    onPrimaryContainer = Color(0xFF121211),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFF4F4F1),
    secondaryContainer = Color(0xFFE0E0DC),
    onSecondaryContainer = Color(0xFF121211),
    tertiary = Color(0xFF3B6D11),
    onTertiary = Color(0xFFF4F4F1),
    tertiaryContainer = Color(0xFFE0E0DC),
    onTertiaryContainer = Color(0xFF3B6D11),
    error = Color(0xFFA32D2D),
    onError = Color(0xFFF4F4F1),
    errorContainer = Color(0xFFF6DCDC),
    onErrorContainer = Color(0xFFA32D2D),
    outline = Color(0xFFCFCFCA),
    outlineVariant = Color(0xFFE0E0DC),
    surface = Color(0xFFEEEEEB),
    onSurface = Color(0xFF121211),
    onSurfaceVariant = Color(0xFF5C5C58),
    surfaceContainerHighest = Color(0xFFE0E0DC),
    shadow = Color(0x00000000),
    keyboardSurface = Color(0xFFE0E0DC),
    keyboardSurfaceDim = Color(0xFFE0E0DC),
    keyboardContainer = Color(0xFFF7F7F5),
    keyboardContainerVariant = Color(0xFFCFCFCA),
    onKeyboardContainer = Color(0xFF121211),
    keyboardPress = Color(0xFFC4C4BF),
    keyboardFade0 = Color(0xFFE0E0DC),
    keyboardFade1 = Color(0xFFE0E0DC),
    primaryTransparent = Color(0xFF000000).copy(alpha = 0.18f),
    onSurfaceTransparent = Color(0xFF121211).copy(alpha = 0.08f),
    navigationBarColor = Color(0xFFE0E0DC),
    keyboardContainerPressed = Color(0xFFC4C4BF),
    onKeyboardContainerPressed = Color(0xFF121211),
    hintColor = Color(0xFF8A8A85),
)

/** Libron on the keys, pill-shaped keycaps, flat (no shadows or outlines). */
private fun KeyboardColorScheme.continuum(context: Context): KeyboardColorScheme {
    val libron = try {
        ResourcesCompat.getFont(context, R.font.libron_regular)
    } catch (_: Exception) {
        null
    }
    return copy(
        extended = extended.copy(
            advancedThemeOptions = extended.advancedThemeOptions.copy(
                keyRoundness = 3.2f,
                keyBorders = true,
                font = libron,
                themeName = "Continuum",
                themeAuthor = "Continuum",
                textSizeMultiplier = 1.06f,
            ),
        ),
    )
}

val ContinuumInk = ThemeOption(
    dynamic = false,
    key = "ContinuumInk",
    name = R.string.theme_continuum_ink,
    available = { true },
) { continuumInk.continuum(it) }

val ContinuumLight = ThemeOption(
    dynamic = false,
    key = "ContinuumLight",
    name = R.string.theme_continuum_light,
    available = { true },
) { continuumLight.continuum(it) }

/** Ink in dark mode, Light in light mode. */
val ContinuumSystem = ThemeOption(
    dynamic = true,
    key = "ContinuumSystem",
    name = R.string.theme_continuum_system,
    available = { true },
) {
    val night = (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    (if (night) continuumInk else continuumLight).continuum(it)
}
