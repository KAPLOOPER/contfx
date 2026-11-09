package dev.continuum.keyboard.layout

import kotlin.math.min

/** Shared by the drawn strip and autofill requests made before the input view is measured. */
internal object KeyboardGeometry {
    fun verticalUnit(widthPx: Int, density: Float, landscape: Boolean): Float =
        min(widthPx / Key.WIDTH, 1.12f * density) * if (landscape) 0.78f else 1f

    fun stripHeightPx(widthPx: Int, density: Float, landscape: Boolean): Int =
        (44f * verticalUnit(widthPx, density, landscape)).toInt().coerceAtLeast(1)
}
