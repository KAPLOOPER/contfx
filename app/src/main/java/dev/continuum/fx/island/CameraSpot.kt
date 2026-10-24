package dev.continuum.fx.island

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.DisplayCutout
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.min

/**
 * Where the front camera sits, in screen pixels. The island's resting ink is a
 * circle exactly over it, so everything else can grow out of the camera.
 */
data class CameraSpot(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val screenWidth: Int,
    val screenHeight: Int,
    /** False when no cutout was found and the spot is a best guess at the top centre. */
    val detected: Boolean,
    val landscape: Boolean,
)

object CameraLocator {

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    fun locate(context: Context, wm: WindowManager): CameraSpot {
        val density = context.resources.displayMetrics.density
        val (w, h) = screenSize(wm)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || w > h

        val rect = topCutout(wm)?.let { cutout ->
            cutout.boundingRects
                .filter { it.top <= (4 * density) && it.width() < w / 2 && !it.isEmpty }
                .minByOrNull { abs(it.exactCenterX() - w / 2f) }
        }
        if (rect != null) {
            val r = (min(rect.width(), rect.height()) / 2f).coerceIn(9f * density, 22f * density)
            return CameraSpot(rect.exactCenterX(), rect.exactCenterY(), r, w, h, detected = true, landscape = landscape)
        }

        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val bar = if (id > 0) context.resources.getDimensionPixelSize(id).toFloat() else 24f * density
        val r = 10f * density
        return CameraSpot(w / 2f, (bar / 2f).coerceAtLeast(r + 2f * density), r, w, h, detected = false, landscape = landscape)
    }

    private fun screenSize(wm: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b: Rect = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        return size.x to size.y
    }

    private fun topCutout(wm: WindowManager): DisplayCutout? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.windowInsets.displayCutout
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.cutout
        }
    } catch (_: Exception) {
        null
    }
}
