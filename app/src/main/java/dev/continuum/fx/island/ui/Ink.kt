package dev.continuum.fx.island.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Shader
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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
 * The feel of ink: one small vocabulary of haptics, used wherever your finger moves ink. A neck
 * letting go is a crisp tick, pieces coming together a softer one, a drop landing (a code
 * copied) a firm confirm, and switches click on and off. Ink that moves by itself (a
 * notification dripping in) stays silent: only what you do is felt. Android's touch-feedback
 * setting is respected throughout.
 */
internal object InkFeel {
    private var last = 0L

    private fun feel(view: View, modern: Int, fallback: Int, sdk: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        // Never a buzz: a burst of changes in one moment is felt once.
        if (now - last < 45) return
        last = now
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= sdk) modern else fallback)
    }

    /** A neck lets go: a drop separates. */
    fun split(view: View) = feel(view, HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.CLOCK_TICK, 34)

    /** Ink comes back together. */
    fun merge(view: View) = feel(view, HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE, HapticFeedbackConstants.CLOCK_TICK, 34)

    /** A step passed: a page handed over mid-swipe. */
    fun detent(view: View) = feel(view, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, HapticFeedbackConstants.CLOCK_TICK, 34)

    /** Something done: a drop landing where you sent it. */
    fun land(view: View) = feel(view, HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.VIRTUAL_KEY, 30)

    /** A switch or chip filling with ink, or draining. */
    fun toggle(view: View, on: Boolean) = feel(
        view,
        if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
        if (on) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.CLOCK_TICK,
        34,
    )
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

/**
 * A neck from [a] (above) down to [b] (below) that leans: it leaves [a] at [x1] and meets
 * [b] at [x2], so a drop dragged sideways stays tied on until the neck thins out and snaps.
 * [k] is how thick it still is, 1 at rest down to 0 when it lets go.
 */
internal fun DrawScope.neckSlant(a: Rect, b: Rect, x1: Float, x2: Float, k: Float, maxHalf: Float, color: Color) {
    if (k <= 0.02f) return
    val d = (b.top - a.bottom).coerceAtLeast(0f)
    val t = min(min(a.width / 2f * 0.72f, maxHalf), min(b.width / 2f * 0.72f, maxHalf)) * 0.8f * k
    val w1 = lerpF(t, min(a.width / 2f * 0.72f, maxHalf), k.pow(0.5f))
    val w2 = lerpF(t, min(b.width / 2f * 0.72f, maxHalf), k.pow(0.5f))
    val o1 = min(a.height * 0.3f, maxHalf)
    val o2 = min(b.height * 0.3f, maxHalf)
    val y1 = a.bottom
    val y2 = b.top
    val ym = (y1 + y2) / 2f
    val xm = (x1 + x2) / 2f
    val path = Path().apply {
        moveTo(x1 - w1, y1 - o1)
        cubicTo(x1 - w1, y1 + d * 0.25f, xm - t, ym - d * 0.2f, xm - t, ym)
        cubicTo(xm - t, ym + d * 0.2f, x2 - w2, y2 - d * 0.25f, x2 - w2, y2 + o2)
        lineTo(x2 + w2, y2 + o2)
        cubicTo(x2 + w2, y2 - d * 0.25f, xm + t, ym + d * 0.2f, xm + t, ym)
        cubicTo(xm + t, ym - d * 0.2f, x1 + w1, y1 + d * 0.25f, x1 + w1, y1 - o1)
        close()
    }
    drawPath(path, color)
}

// ---- Accents: what else the ink can do ----

/**
 * A drop pulled four ways, as a sparkle is. [p] is how round: 2 is a circle, and smaller pulls
 * it out into four points (0.42 a sparkle, 0.6 a fuller star). Written into [into] and returned.
 */
internal fun inkStar(cx: Float, cy: Float, r: Float, p: Float, rot: Float = 0f, into: Path = Path()): Path {
    into.reset()
    val k = p.coerceAtLeast(0.2f)
    for (i in 0..72) {
        val th = i / 72f * 2f * PI.toFloat()
        val c = abs(cos(th))
        val s = abs(sin(th))
        val rr = r / (c.pow(k) + s.pow(k)).pow(1f / k)
        val a = th + rot
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        if (i == 0) into.moveTo(x, y) else into.lineTo(x, y)
    }
    into.close()
    return into
}

/** How far a drop is pulled out at [u] through its glint (0 → 1): out quickly, then round again. */
internal fun glintPull(u: Float): Float = when {
    u <= 0f || u >= 1f -> 0f
    u < 0.22f -> u / 0.22f
    else -> (1f - (u - 0.22f) / 0.5f).coerceAtLeast(0f)
}

/** A glint over [center]: a sparkle of ink that flares out to [radius] and is gone, [u] 0 → 1. */
internal fun DrawScope.glint(center: Offset, radius: Float, u: Float, color: Color, alpha: Float = 1f) {
    if (u <= 0f || u >= 1f) return
    val e = sin(PI.toFloat() * u)
    drawPath(inkStar(center.x, center.y, radius * e, 0.42f, u * 0.9f), color, alpha = alpha * e.coerceIn(0f, 1f))
}

/** The one colour: what needs you is marked with a drop of red, pulled into a star, with a drip. */
internal val MarkRed = Color(0xFFE5342E)

/** The red mark in a box [size] wide, its drip hanging below; [sway] (0..1) lets the drip move. */
internal fun DrawScope.redMark(origin: Offset, size: Float, sway: Float) {
    val cx = origin.x + size / 2f
    val r = size / 2f
    val cy = origin.y + r * 0.82f
    drawPath(inkStar(cx, cy, r * (1f + 0.05f * sin(sway * 2f * PI.toFloat())), 0.6f), MarkRed)
    val tail = Path().apply {
        moveTo(cx - r * 0.13f, cy + r * 0.25f)
        lineTo(cx + r * 0.13f, cy + r * 0.25f)
        lineTo(cx, cy + r * (2.3f + 0.2f * sin(sway * 2f * PI.toFloat() + 1f)))
        close()
    }
    drawPath(tail, MarkRed)
}

/** The red mark as a piece of a row, with its drip moving very slightly. */
@Composable
internal fun RedMark(size: Dp = 20.dp, modifier: Modifier = Modifier) {
    val sway by rememberInfiniteTransition(label = "mark").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "markSway",
    )
    Canvas(modifier.size(size, size * 1.5f)) { redMark(Offset.Zero, this.size.width, sway) }
}

/** A halo: a thin ring of ink floating, tilted, over a drop; [k] grows it in (0 → 1). */
internal fun DrawScope.halo(center: Offset, rx: Float, ry: Float, k: Float, color: Color, stroke: Float) {
    if (k <= 0.01f) return
    rotate(-9f, center) {
        drawOval(
            color,
            topLeft = Offset(center.x - rx * k, center.y - ry * k),
            size = Size(rx * 2f * k, ry * 2f * k),
            alpha = k.coerceIn(0f, 1f),
            style = Stroke(stroke),
        )
    }
}

/** A row of tiny drops from [from] to [to]: a dotted line, in ink. */
internal fun DrawScope.dropsLine(from: Offset, to: Offset, color: Color, gap: Float, radius: Float) {
    val len = (to - from).getDistance()
    val n = (len / gap).toInt()
    if (n <= 0) return
    for (i in 1..n) {
        val t = i / n.toFloat()
        drawCircle(color, radius, Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))
    }
}
