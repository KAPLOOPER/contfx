/*
 * Continuum Keyboard: a modified version of FUTO Keyboard. This file is new in the
 * modified version and is distributed under the same FUTO Source First License 1.1-kb.
 */
package org.futo.inputmethod.keyboard.internal

import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Continuum press: a key squashes in on the Snap spring while held and springs back
 * out on Flow when released, overshooting slightly. One small spring per key that is
 * moving; keys at rest cost nothing.
 */
class KeySquash(private val view: View) {

    private class Spring {
        var x = 0f
        var v = 0f
        var target = 0f
        var pressed = false
    }

    private val springs = HashMap<Any, Spring>()
    private var lastNanos = 0L

    /** Notes a key's press state while it's drawn, and returns how far it's squashed (0 at rest). */
    fun track(key: Any, pressed: Boolean): Float {
        var s = springs[key]
        if (s == null) {
            if (!pressed) return 0f
            s = Spring()
            springs[key] = s
        }
        if (s.pressed != pressed) {
            s.pressed = pressed
            s.target = if (pressed) 1f else 0f
            view.postInvalidateOnAnimation()
        }
        return s.x
    }

    /** Moves every spring on to now. True while any key is still moving. */
    fun step(): Boolean {
        if (springs.isEmpty()) {
            lastNanos = 0L
            return false
        }
        val now = System.nanoTime()
        val dt = (if (lastNanos == 0L) 1f / 60f else (now - lastNanos) / 1e9f).coerceIn(0f, 1f / 30f)
        lastNanos = now
        var active = false
        val it = springs.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            // Snap going in, Flow coming back out.
            val damping = if (s.target > 0.5f) 0.843f else 0.613f
            val stiffness = if (s.target > 0.5f) 620f else 240f
            var remaining = dt
            while (remaining > 0f) {
                val h = min(remaining, 1f / 240f)
                val a = -stiffness * (s.x - s.target) - 2f * damping * sqrt(stiffness) * s.v
                s.v += a * h
                s.x += s.v * h
                remaining -= h
            }
            if (abs(s.x - s.target) < 0.002f && abs(s.v) < 0.01f) {
                s.x = s.target
                s.v = 0f
                if (!s.pressed) it.remove()
            } else {
                active = true
            }
        }
        if (!active) lastNanos = 0L
        return active
    }

    companion object {
        /** Pill keys go wider and shorter, like a bubble pressed flat. */
        const val WIDEN = 0.05f
        const val FLATTEN = 0.14f
    }
}
