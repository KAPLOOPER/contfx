package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tan

/** Small building blocks for the effects: filters, followers and glides, one channel each. */

/** One biquad's running state (transposed direct form II, double precision). */
internal class Bq(var c: BiquadCoefficients) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun run(x: Double): Double {
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }
}

internal object Design {
    private const val BUTTERWORTH = 0.7071067811865476

    fun lowPass(f: Double, sr: Double, q: Double = BUTTERWORTH) =
        BiquadCoefficients.design(EqBand(FilterType.LOW_PASS, f, 0.0, q), sr)

    fun highPass(f: Double, sr: Double, q: Double = BUTTERWORTH) =
        BiquadCoefficients.design(EqBand(FilterType.HIGH_PASS, f, 0.0, q), sr)

    fun peak(f: Double, db: Double, q: Double, sr: Double) =
        BiquadCoefficients.design(EqBand(FilterType.PEAK, f, db, q), sr)
}

/** Fourth-order Linkwitz-Riley low- or high-pass: two Butterworth sections in a row. */
internal class Lr4(f: Double, sr: Double, high: Boolean) {
    private val a = Bq(if (high) Design.highPass(f, sr) else Design.lowPass(f, sr))
    private val b = Bq(if (high) Design.highPass(f, sr) else Design.lowPass(f, sr))
    fun run(x: Double) = b.run(a.run(x))
    fun reset() {
        a.reset()
        b.reset()
    }
}

/**
 * First-order low-pass (bilinear). Its complement, x minus its output, is the matching
 * first-order high-pass exactly, which crossfeed relies on.
 */
internal class OnePoleLp(f: Double, sr: Double) {
    private val k = tan(PI * f / sr)
    private val b0 = k / (1 + k)
    private val a1 = (k - 1) / (k + 1)
    private var x1 = 0.0
    private var y1 = 0.0

    fun run(x: Double): Double {
        val y = b0 * x + b0 * x1 - a1 * y1
        x1 = x
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        y1 = 0.0
    }
}

/** Removes DC and subsonic drift (first order, about [f] Hz). */
internal class DcBlock(f: Double, sr: Double) {
    private val r = 1.0 - 2.0 * PI * f / sr
    private var x1 = 0.0
    private var y1 = 0.0
    fun run(x: Double): Double {
        val y = x - x1 + r * y1
        x1 = x
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        y1 = 0.0
    }
}

/** Peak follower: rises in [attackMs], falls in [releaseMs]. */
internal class Follower(attackMs: Double, releaseMs: Double, sr: Double) {
    private val a = exp(-1.0 / (sr * attackMs / 1000.0))
    private val r = exp(-1.0 / (sr * releaseMs / 1000.0))
    var value = 0.0
        private set

    fun run(level: Double): Double {
        val x = abs(level)
        value = if (x > value) x + (value - x) * a else x + (value - x) * r
        return value
    }

    fun reset() {
        value = 0.0
    }
}

/** A value that glides to its target rather than jumping, so changes never click. */
internal class Glide(timeMs: Double, sr: Double, start: Double = 0.0) {
    private val k = 1.0 - exp(-1.0 / (sr * timeMs / 1000.0))
    var value = start
        private set
    var target = start

    fun next(): Double {
        val d = target - value
        value = if (abs(d) < 1e-7) target else value + d * k
        return value
    }

    fun jump(v: Double) {
        value = v
        target = v
    }

    val settled: Boolean get() = value == target
}

/** A value that moves to its target in a straight line, over [timeMs] for a full 0..1 swing. */
internal class Ramp(timeMs: Double, sr: Double) {
    private val step = 1.0 / (sr * timeMs / 1000.0)
    var value = 0.0
        private set
    var target = 0.0

    fun next(): Double {
        value = if (value < target) minOf(value + step, target) else maxOf(value - step, target)
        return value
    }
}

/**
 * Pure harmonics of whatever's fed in, by Chebyshev polynomials of the signal over its own
 * envelope: for a tone, T2 gives exactly its second harmonic, T3 its third, T4 its fourth. Fed a
 * band-limited signal, nothing lands past the top of the audio band, so nothing aliases and no
 * oversampling is needed. The harmonics follow the music's level, a constant colour at any volume.
 */
internal class Harmonics(attackMs: Double, releaseMs: Double, sr: Double) {
    private val env = Follower(attackMs, releaseMs, sr)

    fun run(x: Double, c2: Double, c3: Double, c4: Double = 0.0): Double {
        val e = env.run(x)
        if (e < 1e-7) return 0.0
        val u = (x / e).coerceIn(-1.2, 1.2)
        val u2 = u * u
        val t2 = 2 * u2 - 1
        val t3 = u * (4 * u2 - 3)
        val t4 = 8 * u2 * u2 - 8 * u2 + 1
        return e * (c2 * t2 + c3 * t3 + c4 * t4)
    }

    fun reset() = env.reset()
}

/** A slow wander in -1..1, for analogue drift. Seeded, so it's the same every run. */
internal class Wander(sr: Double, seed: Long, private val stepSamples: Int = 4096) {
    private var state = seed or 1L
    private val glide = Glide(700.0, sr)
    private var count = 0

    private fun random(): Double {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    fun next(): Double {
        if (count-- <= 0) {
            count = stepSamples
            glide.target = random()
        }
        return glide.next()
    }
}
