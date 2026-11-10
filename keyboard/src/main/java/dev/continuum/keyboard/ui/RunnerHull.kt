package dev.continuum.keyboard.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The Runner theme's ink as it moves: the smallest shape holding both of its ends, each a pill
 * the size of a key (the convex hull of two rounded rectangles). Worked out without allocating,
 * since it's redrawn on every frame while the ink runs.
 */
class RunnerHull {
    /** The outline's corners, clockwise on screen (y down), [count] of them. */
    val xs = FloatArray(POINTS)
    val ys = FloatArray(POINTS)
    var count = 0
        private set

    private val px = FloatArray(POINTS)
    private val py = FloatArray(POINTS)
    private val order = IntArray(POINTS)
    private val stack = IntArray(POINTS * 2)

    /** The outline round a pill at ([ax], [ay]) sized [aw] by [ah], and another at ([bx], [by]). */
    fun build(ax: Float, ay: Float, aw: Float, ah: Float, bx: Float, by: Float, bw: Float, bh: Float) {
        var n = pill(0, ax, ay, aw, ah)
        n = pill(n, bx, by, bw, bh)
        for (i in 0 until n) order[i] = i
        // Left to right (then top to bottom); there are few enough points for an insertion sort.
        for (i in 1 until n) {
            val k = order[i]
            var j = i - 1
            while (j >= 0 && before(k, order[j])) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = k
        }
        // Andrew's monotone chain: one side, then back along the other.
        var m = 0
        for (i in 0 until n) {
            val p = order[i]
            while (m >= 2 && cross(stack[m - 2], stack[m - 1], p) <= 0f) m--
            stack[m++] = p
        }
        val lower = m + 1
        for (i in n - 2 downTo 0) {
            val p = order[i]
            while (m >= lower && cross(stack[m - 2], stack[m - 1], p) <= 0f) m--
            stack[m++] = p
        }
        // The last point is the first again.
        m = (m - 1).coerceAtLeast(0)
        // Clockwise on screen (a positive area with y down), the way the circles beside it are
        // added, so they fill as one shape rather than cancelling where they overlap.
        var area = 0f
        for (i in 0 until m) {
            val a = stack[i]
            val b = stack[(i + 1) % m]
            area += px[a] * py[b] - px[b] * py[a]
        }
        for (i in 0 until m) {
            val s = if (area >= 0f) stack[i] else stack[m - 1 - i]
            xs[i] = px[s]
            ys[i] = py[s]
        }
        count = if (m >= 3) m else 0
    }

    private fun before(a: Int, b: Int) = px[a] < px[b] || (px[a] == px[b] && py[a] < py[b])

    private fun cross(o: Int, a: Int, b: Int) = (px[a] - px[o]) * (py[b] - py[o]) - (py[a] - py[o]) * (px[b] - px[o])

    /** A pill's outline: four rounded corners, nearly as round as it is narrow. */
    private fun pill(start: Int, cx: Float, cy: Float, w: Float, h: Float): Int {
        val r = min(w, h) / 2f * ROUND
        val hw = w / 2f - r
        val hh = h / 2f - r
        var n = start
        for (c in 0 until 4) {
            val ox = if (c == 0 || c == 3) cx + hw else cx - hw
            val oy = if (c < 2) cy + hh else cy - hh
            val from = c * QUARTER
            for (i in 0..ARC) {
                val t = from + i * QUARTER / ARC
                px[n] = ox + r * cos(t)
                py[n] = oy + r * sin(t)
                n++
            }
        }
        return n
    }

    private companion object {
        const val ARC = 6
        const val ROUND = 0.94f
        const val QUARTER = (PI / 2).toFloat()
        const val POINTS = 2 * 4 * (ARC + 1)
    }
}
