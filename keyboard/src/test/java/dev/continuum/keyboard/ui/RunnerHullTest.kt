package dev.continuum.keyboard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunnerHullTest {

    private fun area(h: RunnerHull): Float {
        var a = 0f
        for (i in 0 until h.count) {
            val j = (i + 1) % h.count
            a += h.xs[i] * h.ys[j] - h.xs[j] * h.ys[i]
        }
        return a / 2f
    }

    /** Inside or on the outline, for a clockwise-on-screen (positive area) convex outline. */
    private fun holds(h: RunnerHull, x: Float, y: Float): Boolean {
        for (i in 0 until h.count) {
            val j = (i + 1) % h.count
            val c = (h.xs[j] - h.xs[i]) * (y - h.ys[i]) - (h.ys[j] - h.ys[i]) * (x - h.xs[i])
            if (c < -0.01f) return false
        }
        return true
    }

    @Test
    fun oneKeyIsJustThatKey() {
        val h = RunnerHull()
        h.build(100f, 200f, 33f, 46f, 100f, 200f, 33f, 46f)
        assertTrue(h.count >= 3)
        assertEquals(100f - 16.5f, (0 until h.count).minOf { h.xs[it] }, 0.01f)
        assertEquals(100f + 16.5f, (0 until h.count).maxOf { h.xs[it] }, 0.01f)
        assertEquals(200f - 23f, (0 until h.count).minOf { h.ys[it] }, 0.01f)
        assertEquals(200f + 23f, (0 until h.count).maxOf { h.ys[it] }, 0.01f)
        // Roughly the key's area, less its rounded corners.
        val a = area(h)
        assertTrue("area $a", a > 33f * 46f * 0.8f && a <= 33f * 46f)
    }

    @Test
    fun runningShapeHoldsBothEndsAndWhatsBetween() {
        val h = RunnerHull()
        h.build(40f, 50f, 33f, 46f, 300f, 160f, 152f, 46f)
        assertTrue(area(h) > 0f)
        for ((x, y) in listOf(40f to 50f, 300f to 160f, 170f to 105f, 40f to 30f, 300f + 70f to 160f, 300f - 70f to 175f)) {
            assertTrue("($x, $y) outside", holds(h, x, y))
        }
        assertTrue(!holds(h, 40f, 160f))
        assertTrue(!holds(h, 300f, 40f))
    }

    @Test
    fun alwaysClockwiseOnScreen() {
        val h = RunnerHull()
        for ((ax, bx) in listOf(10f to 300f, 300f to 10f)) {
            for ((ay, by) in listOf(20f to 200f, 200f to 20f, 50f to 50f)) {
                h.build(ax, ay, 33f, 46f, bx, by, 40f, 40f)
                assertTrue(area(h) > 0f)
            }
        }
    }

    @Test
    fun aDropIsRound() {
        val h = RunnerHull()
        h.build(50f, 50f, 14f, 14f, 50f, 50f, 14f, 14f)
        assertTrue(h.count >= 3)
        // Nearly a circle: as wide as it was asked to be, a touch fuller at the diagonals.
        for (i in 0 until h.count) {
            val d = kotlin.math.hypot(h.xs[i] - 50f, h.ys[i] - 50f)
            assertTrue("$d", d >= 6.99f && d <= 7.2f)
        }
    }
}
