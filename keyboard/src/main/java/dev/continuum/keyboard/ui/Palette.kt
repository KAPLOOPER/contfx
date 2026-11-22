package dev.continuum.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.graphics.ColorUtils
import dev.continuum.keyboard.Prefs

/**
 * The keyboard's colours. Ink is pure black; Tint draws the same ink from the wallpaper's
 * colours, deep enough to read as ink.
 */
data class KeyPalette(
    /** The keyboard itself. */
    val mass: Int,
    /** Letter, number and space caps. */
    val key: Int,
    /** Shift, delete, sheet and strip buttons. */
    val fn: Int,
    /** The ink that rises out of a pressed key. */
    val preview: Int,
    val label: Int,
    val sub: Int,
    /** Shift when on, send when there's something to send, the chosen tool. */
    val bright: Int,
    val onBright: Int,
    val trail: Int,
    /**
     * Printed rather than inked: the keys are paper, and the bright ink is a window cut through to
     * a halftone sky, in these [windowInks] (darkest first) on [windowGround].
     */
    val print: Boolean = false,
    /** The paper is dark: the negative print. */
    val dark: Boolean = true,
    val windowInks: IntArray? = null,
    val windowGround: Int = 0,
) {
    companion object {
        val Ink = KeyPalette(
            mass = 0xFF000000.toInt(),
            key = 0xFF2A2A28.toInt(),
            fn = 0xFF1C1C1B.toInt(),
            preview = 0xFF3A3A38.toInt(),
            label = 0xFFF4F4F1.toInt(),
            sub = 0xFF8A8A85.toInt(),
            bright = 0xFFF4F4F1.toInt(),
            onBright = 0xFF000000.toInt(),
            trail = 0xFFF4F4F1.toInt(),
        )

        /** Continuum Kit's poster, by day: grainy paper, ink, and windows to an ultramarine sky. */
        val Paper = KeyPalette(
            mass = 0xFFEDEDE9.toInt(),
            key = 0xFFDEDED9.toInt(),
            fn = 0xFFE3E3DE.toInt(),
            preview = 0xFFF7F7F4.toInt(),
            label = 0xFF0B0B10.toInt(),
            sub = 0xFF55555B.toInt(),
            bright = 0xFF1512D9.toInt(),
            onBright = 0xFFF6F6F3.toInt(),
            trail = 0xFF1512D9.toInt(),
            print = true,
            dark = false,
            windowInks = intArrayOf(0xFF06053F.toInt(), 0xFF1512D9.toInt(), 0xFF6771E4.toInt(), 0xFFC9CEFA.toInt(), 0xFFF4F4F1.toInt()),
            windowGround = 0xFF1512D9.toInt(),
        )

        /** The same print in negative, for the dark: the sky in the windows keeps its colour. */
        val Film = KeyPalette(
            mass = 0xFF101014.toInt(),
            key = 0xFF1D1D24.toInt(),
            fn = 0xFF18181E.toInt(),
            preview = 0xFF1A1A20.toInt(),
            label = 0xFFECECE8.toInt(),
            sub = 0xFFA4A4A8.toInt(),
            bright = 0xFF3B37F0.toInt(),
            onBright = 0xFFF6F6F3.toInt(),
            trail = 0xFFA3ACFF.toInt(),
            print = true,
            dark = true,
            windowInks = intArrayOf(0xFF05053A.toInt(), 0xFF1512D9.toInt(), 0xFF6771E4.toInt(), 0xFFA3ACFF.toInt(), 0xFFF6F6F2.toInt()),
            windowGround = 0xFF02021F.toInt(),
        )

        fun from(context: Context, prefs: Prefs): KeyPalette {
            if (prefs.look == Prefs.LOOK_PRINT) {
                val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return if (night) Film else Paper
            }
            if (prefs.look != Prefs.LOOK_TINT || Build.VERSION.SDK_INT < 31) return Ink
            val r = context.resources
            fun c(id: Int) = r.getColor(id, context.theme)
            fun deep(color: Int, toBlack: Float) = ColorUtils.blendARGB(color, 0xFF000000.toInt(), toBlack)
            val a900 = c(android.R.color.system_accent1_900)
            val a800 = c(android.R.color.system_accent1_800)
            val a700 = c(android.R.color.system_accent1_700)
            return KeyPalette(
                mass = deep(a900, 0.45f),
                key = deep(a800, 0.35f),
                fn = deep(a900, 0.15f),
                preview = deep(a700, 0.3f),
                label = c(android.R.color.system_accent1_50),
                sub = c(android.R.color.system_accent1_300),
                bright = c(android.R.color.system_accent1_100),
                onBright = deep(a900, 0.3f),
                trail = c(android.R.color.system_accent1_100),
            )
        }
    }
}
