package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalised biquad coefficients (a0 = 1), designed with the RBJ audio EQ cookbook,
 * which is what Equalizer APO, AutoEQ and squig.link all assume.
 */
class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Magnitude response in dB at [frequency]. */
    fun magnitudeDb(frequency: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * frequency / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2.0 * w)
        val s2 = sin(2.0 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1.0 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        return 10.0 * log10(max(num, 1e-30) / max(den, 1e-30))
    }

    companion object {
        fun design(band: EqBand, sampleRate: Double): BiquadCoefficients {
            val f = band.frequency.coerceIn(1.0, sampleRate * 0.49)
            val q = band.q.coerceIn(0.025, 50.0)
            val a = 10.0.pow(band.gainDb / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val cw = cos(w0)
            val sw = sin(w0)
            val alpha = sw / (2.0 * q)
            val sqA = sqrt(a)

            // b0, b1, b2, a0, a1, a2
            val c: DoubleArray = when (band.type) {
                FilterType.PEAK -> doubleArrayOf(
                    1.0 + alpha * a, -2.0 * cw, 1.0 - alpha * a,
                    1.0 + alpha / a, -2.0 * cw, 1.0 - alpha / a,
                )
                FilterType.LOW_SHELF -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + 2 * sqA * alpha),
                    2 * a * ((a - 1) - (a + 1) * cw),
                    a * ((a + 1) - (a - 1) * cw - 2 * sqA * alpha),
                    (a + 1) + (a - 1) * cw + 2 * sqA * alpha,
                    -2 * ((a - 1) + (a + 1) * cw),
                    (a + 1) + (a - 1) * cw - 2 * sqA * alpha,
                )
                FilterType.HIGH_SHELF -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + 2 * sqA * alpha),
                    -2 * a * ((a - 1) + (a + 1) * cw),
                    a * ((a + 1) + (a - 1) * cw - 2 * sqA * alpha),
                    (a + 1) - (a - 1) * cw + 2 * sqA * alpha,
                    2 * ((a - 1) - (a + 1) * cw),
                    (a + 1) - (a - 1) * cw - 2 * sqA * alpha,
                )
                FilterType.LOW_PASS -> doubleArrayOf(
                    (1 - cw) / 2, 1 - cw, (1 - cw) / 2,
                    1 + alpha, -2 * cw, 1 - alpha,
                )
                FilterType.HIGH_PASS -> doubleArrayOf(
                    (1 + cw) / 2, -(1 + cw), (1 + cw) / 2,
                    1 + alpha, -2 * cw, 1 - alpha,
                )
                FilterType.NOTCH -> doubleArrayOf(
                    1.0, -2 * cw, 1.0,
                    1 + alpha, -2 * cw, 1 - alpha,
                )
                FilterType.BAND_PASS -> doubleArrayOf(
                    alpha, 0.0, -alpha,
                    1 + alpha, -2 * cw, 1 - alpha,
                )
                FilterType.ALL_PASS -> doubleArrayOf(
                    1 - alpha, -2 * cw, 1 + alpha,
                    1 + alpha, -2 * cw, 1 - alpha,
                )
            }
            val a0 = c[3]
            return BiquadCoefficients(c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0)
        }
    }
}

/** Frequency-response helpers used by the graph and by auto preamp. */
object EqResponse {
    const val SAMPLE_RATE = 48000.0

    /** Log-spaced frequency for point [i] of [points] between 20 Hz and 20 kHz. */
    fun frequencyAt(i: Int, points: Int): Double =
        EqLimits.MIN_FREQ * 10.0.pow(3.0 * i / (points - 1).toDouble())

    fun gainAt(profile: EqProfile, frequency: Double, includePreamp: Boolean = false): Double {
        var db = if (includePreamp) profile.preampDb else 0.0
        for (band in profile.bands) {
            if (!band.enabled) continue
            db += BiquadCoefficients.design(band, SAMPLE_RATE).magnitudeDb(frequency, SAMPLE_RATE)
        }
        return db
    }

    fun curve(profile: EqProfile, points: Int = 200, includePreamp: Boolean = false): DoubleArray {
        val coeffs = profile.bands.filter { it.enabled }.map { BiquadCoefficients.design(it, SAMPLE_RATE) }
        return DoubleArray(points) { i ->
            val f = frequencyAt(i, points)
            var db = if (includePreamp) profile.preampDb else 0.0
            for (c in coeffs) db += c.magnitudeDb(f, SAMPLE_RATE)
            db
        }
    }

    /** Highest boost anywhere in the audible range, ignoring the preamp. */
    fun peakDb(profile: EqProfile): Double = curve(profile, 400).maxOrNull() ?: 0.0

    /**
     * How much louder (or quieter) [profile] makes music overall, preamp included, in dB.
     * Music carries roughly equal energy per octave, so the gain is power-averaged over
     * log-spaced frequencies, across 50 Hz to 12 kHz where hearing judges loudness.
     */
    fun loudnessDb(profile: EqProfile): Double {
        val points = 96
        val lo = log10(50.0)
        val hi = log10(12000.0)
        val coeffs = profile.bands.filter { it.enabled }.map { BiquadCoefficients.design(it, SAMPLE_RATE) }
        var power = 0.0
        for (i in 0 until points) {
            val f = 10.0.pow(lo + (hi - lo) * i / (points - 1))
            var db = profile.preampDb
            for (c in coeffs) db += c.magnitudeDb(f, SAMPLE_RATE)
            power += 10.0.pow(db / 10.0)
        }
        return 10.0 * log10(power / points)
    }

    /** Preamp that keeps the loudest boost at or below 0 dB, rounded to 0.1 dB. */
    fun suggestedPreamp(profile: EqProfile): Double {
        val peak = max(0.0, peakDb(profile))
        return -kotlin.math.ceil(peak * 10.0) / 10.0
    }
}
