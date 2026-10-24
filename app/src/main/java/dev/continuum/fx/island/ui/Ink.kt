package dev.continuum.fx.island.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.min
import kotlin.math.pow

/** Colours on ink. Ink is always pure black, whatever the system theme. */
internal object InkColors {
    val ink = Color(0xFF000000)
    val on = Color(0xFFF4F4F1)
    val sub = Color(0xFFA3A39E)
    val tonal = Color(0xFF242423)
    val raised = Color(0xFF1C1C1B)
    val live = Color(0xFF97C459)
    val onLive = Color(0xFF173404)
    val end = Color(0xFFE24B4A)
    val onEnd = Color(0xFFFFFFFF)
}

/**
 * The goo: blur the ink, then pull the alpha back to a hard edge. Shapes that come
 * close fuse with a smooth neck, and a thin neck vanishes on its own, so ink reads
 * as one liquid. Android 12 and later; older versions rely on the drawn necks alone.
 */
@RequiresApi(31)
internal object Goo {
    fun effect(blurPx: Float): RenderEffect {
        val threshold = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 22f, -10f * 255f,
            ),
        )
        val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.DECAL)
        val sharpen = android.graphics.RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(threshold))
        return android.graphics.RenderEffect.createChainEffect(sharpen, blur).asComposeRenderEffect()
    }
}

/**
 * Remembers whether two pieces of ink are still joined. A neck stretches until it
 * reaches its limit and snaps; it only re-forms once the pieces touch again. Mutated
 * while drawing, so it deliberately isn't snapshot state.
 */
internal class NeckLatch {
    var joined = true
}

internal fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A horizontal neck between [a] (left) and [b] (right). */
internal fun DrawScope.neckAcross(a: Rect, b: Rect, latch: NeckLatch, reach: Float, touch: Float, color: Color) {
    val d = b.left - a.right
    if (d <= touch) {
        latch.joined = true
        return
    }
    if (d >= reach) latch.joined = false
    if (!latch.joined) return
    val k = (1f - d / reach).coerceIn(0f, 1f).pow(1.5f)
    val cy = (a.center.y + b.center.y) / 2f
    val t = min(a.height, b.height) / 2f * 0.72f * k
    // The ends pull back in as the neck thins, so nothing is left behind when it snaps.
    val h1 = lerpF(t, a.height / 2f * 0.72f, k.pow(0.5f))
    val h2 = lerpF(t, b.height / 2f * 0.72f, k.pow(0.5f))
    val o1 = a.height / 2f * 0.5f
    val o2 = b.height / 2f * 0.5f
    val x1 = a.right
    val x2 = b.left
    val xm = (x1 + x2) / 2f
    val path = Path().apply {
        moveTo(x1 - o1, cy - h1)
        cubicTo(x1 + d * 0.25f, cy - h1, xm - d * 0.2f, cy - t, xm, cy - t)
        cubicTo(xm + d * 0.2f, cy - t, x2 - d * 0.25f, cy - h2, x2 + o2, cy - h2)
        lineTo(x2 + o2, cy + h2)
        cubicTo(x2 - d * 0.25f, cy + h2, xm + d * 0.2f, cy + t, xm, cy + t)
        cubicTo(xm - d * 0.2f, cy + t, x1 + d * 0.25f, cy + h1, x1 - o1, cy + h1)
        close()
    }
    drawPath(path, color)
}

/** A vertical neck from [a] (above) down to [b] (below), centred on [cx]. */
internal fun DrawScope.neckDown(
    a: Rect,
    b: Rect,
    cx: Float,
    latch: NeckLatch,
    reach: Float,
    touch: Float,
    maxHalf: Float,
    color: Color,
) {
    val d = b.top - a.bottom
    if (d <= touch) {
        latch.joined = true
        return
    }
    if (d >= reach) latch.joined = false
    if (!latch.joined) return
    val k = (1f - d / reach).coerceIn(0f, 1f).pow(1.5f)
    val t = min(min(a.width / 2f * 0.72f, maxHalf), min(b.width / 2f * 0.72f, maxHalf)) * 0.8f * k
    val w1 = lerpF(t, min(a.width / 2f * 0.72f, maxHalf), k.pow(0.5f))
    val w2 = lerpF(t, min(b.width / 2f * 0.72f, maxHalf), k.pow(0.5f))
    val o1 = min(a.height * 0.3f, maxHalf)
    val o2 = min(b.height * 0.3f, maxHalf)
    val y1 = a.bottom
    val y2 = b.top
    val ym = (y1 + y2) / 2f
    val path = Path().apply {
        moveTo(cx - w1, y1 - o1)
        cubicTo(cx - w1, y1 + d * 0.25f, cx - t, ym - d * 0.2f, cx - t, ym)
        cubicTo(cx - t, ym + d * 0.2f, cx - w2, y2 - d * 0.25f, cx - w2, y2 + o2)
        lineTo(cx + w2, y2 + o2)
        cubicTo(cx + w2, y2 - d * 0.25f, cx + t, ym + d * 0.2f, cx + t, ym)
        cubicTo(cx + t, ym - d * 0.2f, cx + w1, y1 + d * 0.25f, cx + w1, y1 - o1)
        close()
    }
    drawPath(path, color)
}
