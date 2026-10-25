package dev.continuum.fx.dsp

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The EQ as a bank of fine bands, for Direct mode, where Android's DynamicsProcessing effect does
 * the work on each app's own sound. That effect takes bands rather than filters: each band runs
 * from the previous band's cutoff to its own, at one gain. Ninety-six bands, a tenth of an
 * octave each, follow a parametric curve to within a fraction of a decibel.
 */
object BandPlan {
    const val BANDS = 96

    /** Band cutoffs in Hz, log-spaced up to 20 kHz; band i runs from cutoff i-1 (0 for the first). */
    val cutoffs: FloatArray = FloatArray(BANDS) { i -> (EqLimits.MIN_FREQ * 1000.0.pow((i + 1).toDouble() / BANDS)).toFloat() }

    /** Where each band's gain is read from the curve: its middle, on a log scale. */
    private val centres: DoubleArray = DoubleArray(BANDS) { i ->
        val lo = if (i == 0) EqLimits.MIN_FREQ / 1.075 else cutoffs[i - 1].toDouble()
        sqrt(lo * cutoffs[i])
    }

    /** Each band's gain in dB for [profile], without its preamp (that's the effect's input gain). */
    fun gains(profile: EqProfile): FloatArray {
        val coeffs = profile.bands.filter { it.enabled }.map { BiquadCoefficients.design(it, EqResponse.SAMPLE_RATE) }
        return FloatArray(BANDS) { i ->
            var db = 0.0
            for (c in coeffs) db += c.magnitudeDb(centres[i], EqResponse.SAMPLE_RATE)
            db.toFloat()
        }
    }

    /** The band [frequency] falls in. */
    fun bandAt(frequency: Double): Int {
        for (i in 0 until BANDS) if (frequency <= cutoffs[i]) return i
        return BANDS - 1
    }
}
