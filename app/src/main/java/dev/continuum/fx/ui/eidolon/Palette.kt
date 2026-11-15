/*
 * Eidolon: the island's visual language.
 *
 * The palette is authored, not derived from the wallpaper: a sheet of grey paper, one ink and one
 * ultramarine. Continuum's own meanings for colour stay as they were (green is happening now, red
 * needs you; see theme/Continuum.kt), so they are not repeated here.
 */

package dev.continuum.fx.ui.eidolon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

@Immutable
data class EidolonColors(
    val isDark: Boolean,
    /** The sheet everything is printed on. */
    val paper: Color,
    /** A slip pasted onto the sheet: rows, tables. */
    val slip: Color,
    /** A slip lifted off the sheet: bottom sheets, menus. */
    val slipRaised: Color,
    /** A recess in a slip: slider wells, unselected cells. */
    val well: Color,
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    /** Hairline rules between rows and around slips. */
    val rule: Color,
    val ruleStrong: Color,
    /** The one colour. */
    val ultramarine: Color,
    val onUltramarine: Color,
    /** Ultramarine diluted: selection washes, containers. */
    val wash: Color,
    val onWash: Color,
    /** Spent only on what is live: the pupil, the preview trigger, a destructive action. */
    val signal: Color,
    val onSignal: Color,
    val gilt: Color,
    /** Darkest to lightest tone of the dithered sky, and the ground showing between its dots. */
    val sky: List<Color>,
    val skyGround: Color,
    /** The sky with the colour drained out of it, shown while the island sleeps. */
    val skyDormant: List<Color>,
    val skyGroundDormant: Color,
    /**
     * The sky seen through a window cut in the paper. In the negative print the page is greys, and
     * only what is switched on shows colour.
     */
    val windowSky: List<Color>,
    val windowGround: Color,
    val windowSkyDormant: List<Color>,
    val windowGroundDormant: Color,
    /** Type and marks laid over a window. */
    val onWindow: Color,
)

val PaperColors =
    EidolonColors(
        isDark = false,
        paper = Color(0xFFE8E8E4),
        slip = Color(0xFFF2F2EF),
        slipRaised = Color(0xFFF6F6F3),
        well = Color(0xFFE1E1DC),
        ink = Color(0xFF0B0B10),
        inkSoft = Color(0xFF55555B),
        inkFaint = Color(0xFF8E8E92),
        rule = Color(0xFF0B0B10).copy(alpha = 0.14f).compositeOver(Color(0xFFF2F2EF)),
        ruleStrong = Color(0xFF0B0B10),
        ultramarine = Color(0xFF1512D9),
        onUltramarine = Color(0xFFF6F6F3),
        wash = Color(0xFFD3D6FB),
        onWash = Color(0xFF06053F),
        signal = Color(0xFFE5060E),
        onSignal = Color(0xFFFFFFFF),
        gilt = Color(0xFF8C7220),
        sky =
            listOf(
                Color(0xFF06053F),
                Color(0xFF1512D9),
                Color(0xFF6771E4),
                Color(0xFFC9CEFA),
                Color(0xFFF4F4F1),
            ),
        skyGround = Color(0xFF03032A),
        skyDormant =
            listOf(
                Color(0xFF9A9A98),
                Color(0xFFB4B4B1),
                Color(0xFFCBCBC8),
                Color(0xFFDEDEDB),
                Color(0xFFF4F4F1),
            ),
        skyGroundDormant = Color(0xFF7C7C80),
        windowSky =
            listOf(
                Color(0xFF06053F),
                Color(0xFF1512D9),
                Color(0xFF6771E4),
                Color(0xFFC9CEFA),
                Color(0xFFF4F4F1),
            ),
        // Between the dots of a window: ultramarine, not night, so what is switched on reads as a
        // patch of lit sky against the dark one behind the slips.
        windowGround = Color(0xFF1512D9),
        windowSkyDormant =
            listOf(
                Color(0xFF3E3E44),
                Color(0xFF66666C),
                Color(0xFF8E8E92),
                Color(0xFFBDBDBA),
                Color(0xFFF4F4F1),
            ),
        windowGroundDormant = Color(0xFF55555B),
        onWindow = Color(0xFFF6F6F3),
    )

val VoidColors =
    EidolonColors(
        isDark = true,
        paper = Color(0xFF07070A),
        slip = Color(0xFF101014),
        slipRaised = Color(0xFF16161B),
        well = Color(0xFF1D1D24),
        ink = Color(0xFFECECE8),
        inkSoft = Color(0xFFA4A4A8),
        inkFaint = Color(0xFF6C6C72),
        rule = Color(0xFFECECE8).copy(alpha = 0.16f).compositeOver(Color(0xFF101014)),
        ruleStrong = Color(0xFFECECE8),
        ultramarine = Color(0xFFA3ACFF),
        onUltramarine = Color(0xFF05053A),
        wash = Color(0xFF1B1D5C),
        onWash = Color(0xFFDDE0FF),
        signal = Color(0xFFFF2B33),
        onSignal = Color(0xFF14000A),
        gilt = Color(0xFFD9C27A),
        sky =
            listOf(
                Color(0xFF020206),
                Color(0xFF1B1B22),
                Color(0xFF6E6E74),
                Color(0xFFC4C4C4),
                Color(0xFFF6F6F2),
            ),
        skyGround = Color(0xFF000000),
        skyDormant =
            listOf(
                Color(0xFF020206),
                Color(0xFF0E0E12),
                Color(0xFF1C1C22),
                Color(0xFF2E2E34),
                Color(0xFF45454B),
            ),
        skyGroundDormant = Color(0xFF000000),
        windowSky =
            listOf(
                Color(0xFF05053A),
                Color(0xFF1512D9),
                Color(0xFF6771E4),
                Color(0xFFA3ACFF),
                Color(0xFFF6F6F2),
            ),
        windowGround = Color(0xFF02021F),
        windowSkyDormant =
            listOf(
                Color(0xFF1B1B22),
                Color(0xFF34343C),
                Color(0xFF5E5E66),
                Color(0xFF9A9AA0),
                Color(0xFFE4E4E0),
            ),
        windowGroundDormant = Color(0xFF08080C),
        onWindow = Color(0xFFF6F6F3),
    )

/** The palette everything is currently printed in: the paper's, or the sky's. */
val LocalEidolonColors = staticCompositionLocalOf { PaperColors }

object Eidolon {
    val colors: EidolonColors
        @Composable @ReadOnlyComposable
        get() = LocalEidolonColors.current
}
