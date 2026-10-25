package dev.continuum.fx.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Real-time parametric EQ for interleaved float audio.
 *
 * The UI thread calls [setProfile]; the audio thread picks the new coefficients up
 * at the start of the next [process] call, so there are no locks on the audio path.
 * Filters run as transposed direct form II in double precision, which stays stable
 * for the very low shelves headphone corrections often use.
 */
class ParametricEq(
    private val channels: Int = 2,
    private val sampleRate: Double = EqResponse.SAMPLE_RATE,
) {
    private class Stage(val coeffs: Array<BiquadCoefficients>, val preamp: Double, val bypass: Boolean)

    private val pending = AtomicReference<Stage?>(null)
    private var stage = Stage(emptyArray(), 1.0, true)
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)
    private var scratch = FloatArray(0)

    /** Pass null to bypass the EQ entirely. */
    fun setProfile(profile: EqProfile?) {
        if (profile == null) {
            pending.set(Stage(emptyArray(), 1.0, true))
            return
        }
        val coeffs = profile.bands
            .filter { it.enabled }
            .map { BiquadCoefficients.design(it, sampleRate) }
            .toTypedArray()
        pending.set(Stage(coeffs, 10.0.pow(profile.preampDb / 20.0), false))
    }

    /**
     * Filters [frames] frames in place. When the settings have changed, this chunk runs
     * through both the old and the new filters and fades from one to the other, so
     * dragging a band or switching profiles never clicks.
     */
    fun process(buffer: FloatArray, frames: Int) {
        val next = pending.getAndSet(null)
        if (next == null) {
            run(stage, buffer, frames, z1, z2)
            return
        }
        val old = stage
        if (old.bypass && next.bypass) return
        val fade = minOf(frames, FADE_FRAMES)
        val faded = fade * channels
        if (scratch.size < faded) scratch = FloatArray(faded)
        System.arraycopy(buffer, 0, scratch, 0, faded)

        // The new filters start from where the old ones were, when they line up one to one.
        val same = next.coeffs.size == old.coeffs.size
        val n1 = if (same) z1.copyOf() else DoubleArray(next.coeffs.size * channels)
        val n2 = if (same) z2.copyOf() else DoubleArray(next.coeffs.size * channels)
        run(old, scratch, fade, z1, z2)
        run(next, buffer, frames, n1, n2)
        val inv = 1f / fade
        for (frame in 0 until fade) {
            val t = (frame + 1) * inv
            val base = frame * channels
            for (ch in 0 until channels) {
                val i = base + ch
                buffer[i] = scratch[i] + (buffer[i] - scratch[i]) * t
            }
        }
        z1 = n1
        z2 = n2
        stage = next
    }

    private fun run(s: Stage, buffer: FloatArray, frames: Int, st1: DoubleArray, st2: DoubleArray) {
        if (s.bypass) return
        val coeffs = s.coeffs
        val n = coeffs.size
        val pre = s.preamp
        for (frame in 0 until frames) {
            val base = frame * channels
            for (ch in 0 until channels) {
                val idx = base + ch
                var x = buffer[idx] * pre
                for (b in 0 until n) {
                    val c = coeffs[b]
                    val zi = b * channels + ch
                    val y = c.b0 * x + st1[zi]
                    st1[zi] = c.b1 * x - c.a1 * y + st2[zi]
                    st2[zi] = c.b2 * x - c.a2 * y
                    x = y
                }
                buffer[idx] = x.toFloat()
            }
        }
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    private companion object {
        /** 10 ms at 48 kHz: long enough to hide a change, short enough to feel instant. */
        const val FADE_FRAMES = 480
    }
}

/**
 * Safety limiter that sits after the EQ so boosts never clip: instant attack,
 * smooth release, then a hard ceiling as the very last guard.
 */
class Limiter(
    sampleRate: Double = EqResponse.SAMPLE_RATE,
    ceilingDb: Double = -0.3,
    releaseMs: Double = 80.0,
    private val channels: Int = 2,
) {
    private val ceiling = 10.0.pow(ceilingDb / 20.0)
    private val release = exp(-1.0 / (sampleRate * releaseMs / 1000.0))
    private var gain = 1.0

    /**
     * Pure dynamics: rather than a brick wall at the ceiling, peaks meet a soft 2:1 shoulder from
     * 2 dB below it (so they're bent, not flattened), with the ceiling still the last word.
     */
    @Volatile
    var soft = false

    /** Current gain reduction in dB (0 when not limiting). */
    val reductionDb: Double get() = if (gain >= 1.0) 0.0 else 20.0 * kotlin.math.log10(gain)

    fun process(buffer: FloatArray, frames: Int) {
        val shoulder = if (soft) ceiling * SHOULDER else Double.MAX_VALUE
        for (frame in 0 until frames) {
            val base = frame * channels
            var peak = 0.0
            for (ch in 0 until channels) peak = max(peak, abs(buffer[base + ch].toDouble()))
            val needed = when {
                // 2:1 above the shoulder, in dB: out = s * sqrt(peak / s), capped at the ceiling.
                peak > shoulder -> kotlin.math.min(shoulder * kotlin.math.sqrt(peak / shoulder), ceiling) / peak
                peak > ceiling -> ceiling / peak
                else -> 1.0
            }
            gain = if (needed < gain) needed else needed + (gain - needed) * release
            for (ch in 0 until channels) {
                val v = (buffer[base + ch] * gain).toFloat()
                buffer[base + ch] = v.coerceIn(-1f, 1f)
            }
        }
    }

    private companion object {
        /** 2 dB below the ceiling. */
        const val SHOULDER = 0.7943282347242815
    }
}
