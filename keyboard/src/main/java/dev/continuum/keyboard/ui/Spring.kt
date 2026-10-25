package dev.continuum.keyboard.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Continuum's two springs: Flow for most motion, Snap for presses. */
class SpringSpec(val damping: Float, val stiffness: Float) {
    companion object {
        val Flow = SpringSpec(0.613f, 240f)
        val Snap = SpringSpec(0.843f, 620f)
    }
}

/** A value that moves to its target on a spring, stepped once per frame. */
class Spring(initial: Float = 0f) {
    var value = initial
        private set
    var target = initial
        private set
    var velocity = 0f
        private set
    private var spec = SpringSpec.Flow

    /** Seconds to wait before moving (for staggered entrances). */
    var delay = 0f

    val moving: Boolean get() = value != target || velocity != 0f || delay > 0f

    fun to(target: Float, spec: SpringSpec = SpringSpec.Flow, delay: Float = 0f) {
        this.target = target
        this.spec = spec
        this.delay = delay
    }

    fun snap(v: Float) {
        value = v; target = v; velocity = 0f; delay = 0f
    }

    /** Advances [dt] seconds. Returns true while still moving. */
    fun step(dt: Float): Boolean {
        if (!moving) return false
        var left = dt
        if (delay > 0f) {
            val used = min(delay, left)
            delay -= used
            left -= used
            if (delay > 0f) return true
        }
        val c = 2f * spec.damping * sqrt(spec.stiffness)
        while (left > 0f) {
            val h = min(left, 1f / 240f)
            val a = -spec.stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            left -= h
        }
        val scale = max(1f, abs(target))
        if (abs(value - target) < 0.0008f * scale && abs(velocity) < 0.01f * scale) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }
}
