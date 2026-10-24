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

    fun process(buffer: FloatArray, frames: Int) {
        pending.getAndSet(null)?.let { next ->
            if (next.coeffs.size != stage.coeffs.size) {
                z1 = DoubleArray(next.coeffs.size * channels)
                z2 = DoubleArray(next.coeffs.size * channels)
            }
            stage = next
        }
        val s = stage
        if (s.bypass) return
        val coeffs = s.coeffs
        val n = coeffs.size
        val pre = s.preamp
        val st1 = z1
        val st2 = z2
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

    /** Current gain reduction in dB (0 when not limiting). */
    val reductionDb: Double get() = if (gain >= 1.0) 0.0 else 20.0 * kotlin.math.log10(gain)

    fun process(buffer: FloatArray, frames: Int) {
        for (frame in 0 until frames) {
            val base = frame * channels
            var peak = 0.0
            for (ch in 0 until channels) peak = max(peak, abs(buffer[base + ch].toDouble()))
            val needed = if (peak > ceiling) ceiling / peak else 1.0
            gain = if (needed < gain) needed else needed + (gain - needed) * release
            for (ch in 0 until channels) {
                val v = (buffer[base + ch] * gain).toFloat()
                buffer[base + ch] = v.coerceIn(-1f, 1f)
            }
        }
    }
}
