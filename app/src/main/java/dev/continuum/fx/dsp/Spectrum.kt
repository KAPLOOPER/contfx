package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A ring of the most recent processed samples (mono), written by the audio thread and
 * read by the screen. No locks: a torn read only ever smudges one frame of a picture.
 */
class SpectrumTap(size: Int = 8192) {
    private val ring = FloatArray(size)
    private val mask = size - 1
    @Volatile
    private var write = 0

    init {
        require(size and mask == 0) { "size must be a power of two" }
    }

    fun push(buffer: FloatArray, frames: Int, channels: Int) {
        var w = write
        for (f in 0 until frames) {
            var sum = 0f
            val base = f * channels
            for (c in 0 until channels) sum += buffer[base + c]
            ring[w] = sum / channels
            w = (w + 1) and mask
        }
        write = w
    }

    /** Copies the latest [out].size samples, oldest first. */
    fun latest(out: FloatArray) {
        val end = write
        val n = out.size
        for (i in 0 until n) out[i] = ring[(end - n + i) and mask]
    }
}

/**
 * Turns audio into [bands] levels spread like hearing (log spaced from 20 Hz to 20 kHz),
 * each 0..1, rising quickly and falling slowly so the picture moves like liquid.
 */
class SpectrumAnalyzer(
    private val fftSize: Int = 4096,
    val bands: Int = 56,
    private val sampleRate: Double = EqResponse.SAMPLE_RATE,
) {
    private val window = FloatArray(fftSize) { (0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1))).toFloat() }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val power = DoubleArray(fftSize / 2)
    private val levels = FloatArray(bands)
    /** Band edges in (fractional) FFT bins. */
    private val edges = DoubleArray(bands + 1)
    private val bits = Integer.numberOfTrailingZeros(fftSize)
    private val cosTable = DoubleArray(fftSize / 2) { cos(2.0 * PI * it / fftSize) }
    private val sinTable = DoubleArray(fftSize / 2) { sin(2.0 * PI * it / fftSize) }

    init {
        require(fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
        val binHz = sampleRate / fftSize
        for (b in 0..bands) {
            val f = EqLimits.MIN_FREQ * 10.0.pow(3.0 * b / bands)
            edges[b] = (f / binHz).coerceIn(0.0, fftSize / 2.0 - 1.0)
        }
    }

    /** Power at a fractional bin, read between its neighbours. */
    private fun powerAt(bin: Double): Double {
        val i = bin.toInt().coerceIn(0, fftSize / 2 - 2)
        val t = (bin - i).coerceIn(0.0, 1.0)
        return power[i] + (power[i + 1] - power[i]) * t
    }

    /** Analyses [samples] (exactly fftSize long) into [out] (bands long). */
    fun analyze(samples: FloatArray, out: FloatArray, dt: Float) {
        for (i in 0 until fftSize) {
            re[i] = (samples[i] * window[i]).toDouble()
            im[i] = 0.0
        }
        fft()
        val norm = 4.0 / (fftSize * fftSize)
        for (k in 0 until fftSize / 2) power[k] = (re[k] * re[k] + im[k] * im[k]) * norm
        val rise = 1f - 0.0005f.pow(dt)
        val fall = 1f - 0.08f.pow(dt)
        for (b in 0 until bands) {
            val lo = edges[b]
            val hi = edges[b + 1]
            val first = kotlin.math.ceil(lo).toInt()
            val last = kotlin.math.floor(hi).toInt()
            val energy = if (last - first < 1) {
                // Narrower than a bin (the bass): read the spectrum at the band's centre.
                powerAt((lo + hi) / 2.0)
            } else {
                var sum = 0.0
                var peak = 0.0
                for (k in first..last.coerceAtMost(fftSize / 2 - 1)) {
                    sum += power[k]
                    if (power[k] > peak) peak = power[k]
                }
                // A blend of the band's peak and its average reads well for both tones and noise.
                0.6 * peak + 0.4 * sum / (last - first + 1)
            }
            val db = 10.0 * log10(energy + 1e-12)
            val target = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
            val cur = levels[b]
            levels[b] = cur + (target - cur) * (if (target > cur) rise else fall)
            out[b] = levels[b]
        }
    }

    /** Lets the picture sink to rest when nothing is playing. */
    fun settle(out: FloatArray, dt: Float) {
        val fall = 1f - 0.08f.pow(dt)
        for (b in 0 until bands) {
            levels[b] -= levels[b] * fall
            out[b] = levels[b]
        }
    }

    private fun fft() {
        // Bit-reversal permutation.
        for (i in 0 until fftSize) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= fftSize) {
            val half = len / 2
            val step = fftSize / len
            var start = 0
            while (start < fftSize) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                start += len
            }
            len *= 2
        }
    }

    companion object {
        const val FLOOR_DB = -78.0
        const val CEIL_DB = -12.0

        /** Centre frequency of band [b] of [bands]. */
        fun centre(b: Int, bands: Int): Double = EqLimits.MIN_FREQ * 10.0.pow(3.0 * (b + 0.5) / bands)

        /** Root-mean-square of a block, for quick silence checks. */
        fun rms(samples: FloatArray): Double {
            var s = 0.0
            for (v in samples) s += v * v
            return sqrt(s / samples.size)
        }
    }
}
