package dev.continuum.fx.ui

import dev.continuum.fx.dsp.EqLimits
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Number formatting and slider mappings for the EQ controls. */
object Fmt {

    fun freq(f: Double): String = when {
        f >= 10000 -> "${(f / 1000).roundToInt()} kHz"
        f >= 1000 -> String.format(Locale.US, "%.1f kHz", f / 1000)
        f >= 100 -> "${f.roundToInt()} Hz"
        else -> String.format(Locale.US, "%.1f Hz", f).replace(".0 Hz", " Hz")
    }

    fun db(d: Double): String {
        val sign = when {
            d > 0.05 -> "+"
            d < -0.05 -> "−"
            else -> ""
        }
        return sign + String.format(Locale.US, "%.1f", abs(d)) + " dB"
    }

    fun q(q: Double): String = String.format(Locale.US, "%.2f", q)

    fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    fun roundFreq(f: Double): Double {
        val c = f.coerceIn(EqLimits.MIN_FREQ, EqLimits.MAX_FREQ)
        return when {
            c < 100 -> (c * 10).roundToInt() / 10.0
            c < 1000 -> c.roundToInt().toDouble()
            else -> (c / 10).roundToInt() * 10.0
        }
    }

    fun roundQ(q: Double): Double = (q.coerceIn(EqLimits.MIN_Q, EqLimits.MAX_Q) * 100).roundToInt() / 100.0

    fun freqToT(f: Double): Float = (log10(f / EqLimits.MIN_FREQ) / 3.0).toFloat().coerceIn(0f, 1f)

    fun tToFreq(t: Float): Double = EqLimits.MIN_FREQ * 10.0.pow(3.0 * t)

    fun qToT(q: Double): Float = ((log10(q) + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)

    fun tToQ(t: Float): Double = 10.0.pow(2.0 * t - 1.0)
}
