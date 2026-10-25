package dev.continuum.keyboard.ui

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * Continuum's line icons, drawn on a 24-unit grid with a 1.8 stroke and round ends, shared
 * by the keys (drawn by hand) and the panels (drawn by Compose).
 */
object Icons {
    const val SHIFT = "M12 4l7.5 8H15v7H9v-7H4.5z"
    const val DELETE = "M9 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H9l-6-6z M12 10l4 4 M16 10l-4 4"
    const val RETURN = "M19 6v5a3 3 0 0 1-3 3H6 M10 10l-4 4 4 4"
    const val CLIPBOARD = "M9 4h6v3H9z M8 5.5H6.5A1.5 1.5 0 0 0 5 7v11.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V7a1.5 1.5 0 0 0-1.5-1.5H16"
    const val EMOJI = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M8.5 14a4 4 0 0 0 7 0 M9 9.5v0.5 M15 9.5v0.5"
    const val SCAN = "M4 8V6a2 2 0 0 1 2-2h2 M16 4h2a2 2 0 0 1 2 2v2 M20 16v2a2 2 0 0 1-2 2h-2 M8 20H6a2 2 0 0 1-2-2v-2 M8 10h8 M8 14h5"
    const val SETTINGS = "M4 7h9 M17 7h3 M4 17h3 M11 17h9 M15 5a2 2 0 1 0 0 4a2 2 0 1 0 0-4z M9 15a2 2 0 1 0 0 4a2 2 0 1 0 0-4z"
    const val HIDE = "M6 9l6 6 6-6"
    const val HUB = "M5 5h5v5H5z M14 5h5v5h-5z M5 14h5v5H5z M14 14h5v5h-5z"
    const val CLOSE = "M7 7l10 10 M17 7L7 17"
    const val CHECK = "M5 12l5 5 9-10"
    const val PIN = "M9 4h6l-1 6 3 3v1H7v-1l3-3z M12 14v6"
    const val TRASH = "M5 7h14 M10 7V5h4v2 M7 7l1 12h8l1-12"
    const val PASTE = "M9 4h6v3H9z M8 5.5H6.5A1.5 1.5 0 0 0 5 7v11.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V7a1.5 1.5 0 0 0-1.5-1.5H16 M9 13h6"
    const val LEFT = "M15 6l-6 6 6 6"
    const val RIGHT = "M9 6l6 6-6 6"

    // Emoji groups.
    const val RECENT = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 7v5l3 2"
    const val PEOPLE = "M12 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z M5 20a7 7 0 0 1 14 0"
    const val NATURE = "M5 19c0-8 5-14 14-14 0 9-6 14-14 14z M5 19l8-8"
    const val FOOD = "M5 9h11v5a5 5 0 0 1-5 5h-1a5 5 0 0 1-5-5z M16 10h1.5a2.5 2.5 0 0 1 0 5H16 M9 3v3 M12 3v3"
    const val TRAVEL = "M5 16v-4l2-5h10l2 5v4z M5 16v2 M19 16v2 M4 12h16 M8 14h0.01 M16 14h0.01"
    const val ACTIVITY = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z M12 3c3 3 3 15 0 18 M12 3c-3 3-3 15 0 18 M3 12h18"
    const val OBJECTS = "M9 18h6 M10 21h4 M12 3a6 6 0 0 0-3.5 10.9V16h7v-2.1A6 6 0 0 0 12 3z"
    const val SYMBOLS = "M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"
    const val FLAGS = "M6 21V4 M6 4h11l-2 4 2 4H6"

    // Scan text.
    const val SHUTTER = "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16z"
    const val RETAKE = "M4 12a8 8 0 1 0 2.5-5.8 M4 4v4h4"
    const val SELECT_ALL = "M4 6h16 M4 12h16 M4 18h10"

    private val cache = HashMap<String, Path>()

    fun path(d: String): Path = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }

    private val matrix = Matrix()
    private val scratch = Path()

    /** Draws icon [d] centred at (cx, cy), [size] pixels across. Filled when [fill]. */
    fun draw(canvas: Canvas, d: String, cx: Float, cy: Float, size: Float, paint: Paint, fill: Boolean = false) {
        val s = size / 24f
        matrix.reset()
        matrix.setScale(s, s)
        matrix.postTranslate(cx - size / 2f, cy - size / 2f)
        scratch.reset()
        path(d).transform(matrix, scratch)
        val oldStyle = paint.style
        val oldWidth = paint.strokeWidth
        paint.style = if (fill) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        paint.strokeWidth = 1.8f * s
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(scratch, paint)
        paint.style = oldStyle
        paint.strokeWidth = oldWidth
    }
}
