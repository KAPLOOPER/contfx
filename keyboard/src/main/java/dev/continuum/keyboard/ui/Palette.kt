package dev.continuum.keyboard.ui

import android.content.Context
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

        fun from(context: Context, prefs: Prefs): KeyPalette {
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
