/*
 * The line the battery runs along: the star's own outline, pushed out evenly all the way round
 * and rounded over each of its four tips, so the sliver keeps the same distance from the star on
 * its long thin arms and in its deep sides alike. It starts at the top tip and runs clockwise,
 * the way a clock face does.
 */

package dev.continuum.fx.island.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The star's outline (half-width [rx], half-height [ry], drawn in by [pinch] as Eidolon's Star
 * is) pushed [gap] pixels outward, as a polyline about the star's centre.
 */
internal class StarHalo(rx: Float, ry: Float, pinch: Float, gap: Float, steps: Int = 26, capSteps: Int = 10) {
    private val xs: FloatArray
    private val ys: FloatArray
    private val at: FloatArray

    /** The whole way round, in pixels. */
    val length: Float

    init {
        val k = 1f - pinch
        // The star's four sides, tip to tip, clockwise from the top: Eidolon's Star.path exactly.
        val sides = arrayOf(
            floatArrayOf(0f, -ry, 0f, -ry * k, rx * k, 0f, rx, 0f),
            floatArrayOf(rx, 0f, rx * k, 0f, 0f, ry * k, 0f, ry),
            floatArrayOf(0f, ry, 0f, ry * k, -rx * k, 0f, -rx, 0f),
            floatArrayOf(-rx, 0f, -rx * k, 0f, 0f, -ry * k, 0f, -ry),
        )
        // Each tip, and the angle (y down, clockwise) the outline leaves it pointing: a half turn
        // of cap carries the line over the tip from one side's normal to the next's.
        val tips = arrayOf(
            floatArrayOf(0f, -ry, 180f),
            floatArrayOf(rx, 0f, 270f),
            floatArrayOf(0f, ry, 0f),
            floatArrayOf(-rx, 0f, 90f),
        )
        val px = ArrayList<Float>(4 * (steps + capSteps) + 8)
        val py = ArrayList<Float>(px.size)
        fun cap(tip: FloatArray, from: Float, to: Float) {
            for (i in 0..capSteps) {
                val a = (from + (to - from) * i / capSteps) * (PI / 180.0)
                px += tip[0] + cos(a).toFloat() * gap
                py += tip[1] + sin(a).toFloat() * gap
            }
        }
        fun side(c: FloatArray) {
            for (i in 1 until steps) {
                val t = i / steps.toFloat()
                val u = 1f - t
                val bx = u * u * u * c[0] + 3f * u * u * t * c[2] + 3f * u * t * t * c[4] + t * t * t * c[6]
                val by = u * u * u * c[1] + 3f * u * u * t * c[3] + 3f * u * t * t * c[5] + t * t * t * c[7]
                val tx = 3f * u * u * (c[2] - c[0]) + 6f * u * t * (c[4] - c[2]) + 3f * t * t * (c[6] - c[4])
                val ty = 3f * u * u * (c[3] - c[1]) + 6f * u * t * (c[5] - c[3]) + 3f * t * t * (c[7] - c[5])
                val n = hypot(tx, ty).coerceAtLeast(1e-4f)
                // Clockwise on a y-down screen, so the outward normal is the tangent turned left.
                px += bx + ty / n * gap
                py += by - tx / n * gap
            }
        }
        // From the very top, round the top tip's second half, then side, tip, side…
        cap(tips[0], 270f, 360f)
        side(sides[0])
        cap(tips[1], 270f, 450f)
        side(sides[1])
        cap(tips[2], 0f, 180f)
        side(sides[2])
        cap(tips[3], 90f, 270f)
        side(sides[3])
        cap(tips[0], 180f, 270f)
        xs = px.toFloatArray()
        ys = py.toFloatArray()
        at = FloatArray(xs.size)
        for (i in 1 until xs.size) at[i] = at[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        length = at.last()
    }

    private fun index(d: Float): Int {
        var lo = 0
        var hi = at.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (at[mid] <= d) lo = mid else hi = mid
        }
        return lo
    }

    /** The point [f] of the way round (0 the top, 0.5 the bottom), about the star's centre. */
    fun point(f: Float): Offset {
        val d = f.coerceIn(0f, 1f) * length
        val i = index(d)
        val j = (i + 1).coerceAtMost(xs.size - 1)
        val span = (at[j] - at[i]).coerceAtLeast(1e-4f)
        val t = ((d - at[i]) / span).coerceIn(0f, 1f)
        return Offset(xs[i] + (xs[j] - xs[i]) * t, ys[i] + (ys[j] - ys[i]) * t)
    }

    /** The stretch from [from] to [to] of the way round, written into [into] about ([cx], [cy]). */
    fun trace(from: Float, to: Float, cx: Float, cy: Float, into: Path): Path {
        into.rewind()
        val a = from.coerceIn(0f, 1f)
        val b = to.coerceIn(0f, 1f)
        if (b - a <= 0f) return into
        val start = point(a)
        into.moveTo(cx + start.x, cy + start.y)
        val da = a * length
        val db = b * length
        var i = index(da) + 1
        while (i < xs.size && at[i] < db) {
            into.lineTo(cx + xs[i], cy + ys[i])
            i++
        }
        val end = point(b)
        into.lineTo(cx + end.x, cy + end.y)
        if (b >= 1f && a <= 0f) into.close()
        return into
    }
}
