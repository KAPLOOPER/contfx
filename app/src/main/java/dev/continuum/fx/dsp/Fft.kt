package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A real FFT of size [n] (a power of two), done as a complex FFT of half the size with the
 * usual untangling. Tables are made once; nothing is allocated while transforming, so it's safe
 * on the audio thread. Spectra have n/2 + 1 bins, kept as separate real and imaginary arrays.
 */
internal class RealFft(val n: Int) {
    init {
        require(n >= 4 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    val bins = n / 2 + 1
    private val m = n / 2
    private val zr = FloatArray(m)
    private val zi = FloatArray(m)
    private val rev = IntArray(m)
    private val cosM = FloatArray(m / 2)
    private val sinM = FloatArray(m / 2)
    // e^{-2πik/n} for the untangling, k < n/2.
    private val cosN = FloatArray(m)
    private val sinN = FloatArray(m)

    init {
        var bits = 0
        while (1 shl bits < m) bits++
        for (i in 0 until m) {
            var r = 0
            var x = i
            repeat(bits) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
        for (k in 0 until m / 2) {
            cosM[k] = cos(2 * PI * k / m).toFloat()
            sinM[k] = -sin(2 * PI * k / m).toFloat()
        }
        for (k in 0 until m) {
            cosN[k] = cos(2 * PI * k / n).toFloat()
            sinN[k] = -sin(2 * PI * k / n).toFloat()
        }
    }

    /** [x] (n samples) to its spectrum. */
    fun forward(x: FloatArray, re: FloatArray, im: FloatArray) {
        for (i in 0 until m) {
            val j = rev[i]
            zr[j] = x[2 * i]
            zi[j] = x[2 * i + 1]
        }
        complex(inverse = false)
        // X[k] = E[k] + W^k O[k], where E and O are the even and odd samples' spectra.
        re[0] = zr[0] + zi[0]
        im[0] = 0f
        re[m] = zr[0] - zi[0]
        im[m] = 0f
        for (k in 1 until m) {
            val ar = zr[k]
            val ai = zi[k]
            val br = zr[m - k]
            val bi = -zi[m - k]
            val er = 0.5f * (ar + br)
            val ei = 0.5f * (ai + bi)
            // O = (Z - conj Z[m-k]) / 2i
            val or = 0.5f * (ai - bi)
            val oi = -0.5f * (ar - br)
            val wr = cosN[k]
            val wi = sinN[k]
            re[k] = er + wr * or - wi * oi
            im[k] = ei + wr * oi + wi * or
        }
    }

    /** A spectrum back to [out] (n samples), scaled so that inverse(forward(x)) is x. */
    fun inverse(re: FloatArray, im: FloatArray, out: FloatArray) {
        for (k in 0 until m) {
            val ar = re[k]
            val ai = im[k]
            val br = re[m - k]
            val bi = -im[m - k]
            val er = 0.5f * (ar + br)
            val ei = 0.5f * (ai + bi)
            // O = W^-k (X - conj X[m-k]) / 2
            val dr = 0.5f * (ar - br)
            val di = 0.5f * (ai - bi)
            val wr = cosN[k]
            val wi = -sinN[k]
            val or = dr * wr - di * wi
            val oi = dr * wi + di * wr
            // Z = E + iO
            val j = rev[k]
            zr[j] = er - oi
            zi[j] = ei + or
        }
        complex(inverse = true)
        val s = 1f / m
        for (i in 0 until m) {
            out[2 * i] = zr[i] * s
            out[2 * i + 1] = zi[i] * s
        }
    }

    /** In-place iterative radix-2 on bit-reversed input. */
    private fun complex(inverse: Boolean) {
        var size = 2
        while (size <= m) {
            val half = size / 2
            val step = m / size
            var start = 0
            while (start < m) {
                var t = 0
                for (j in start until start + half) {
                    val wr = cosM[t]
                    val wi = if (inverse) -sinM[t] else sinM[t]
                    val k = j + half
                    val xr = zr[k] * wr - zi[k] * wi
                    val xi = zr[k] * wi + zi[k] * wr
                    zr[k] = zr[j] - xr
                    zi[k] = zi[j] - xi
                    zr[j] += xr
                    zi[j] += xi
                    t += step
                }
                start += size
            }
            size *= 2
        }
    }
}
