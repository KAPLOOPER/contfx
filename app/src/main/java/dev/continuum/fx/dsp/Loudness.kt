package dev.continuum.fx.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Hearing's balance changes with level: turned down, bass (and the very top) fade far faster than
 * the middle, so a mix balanced loud sounds thin quietly. This is the ISO 226:2003 model of equal
 * loudness, and the correction that keeps the balance as the volume comes down.
 */
object Loudness {
    /** ISO 226:2003 Table 1: the standard's 29 frequencies, with af, Lu and Tf at each. */
    private val F = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0,
        630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0,
    )
    private val AF = doubleArrayOf(
        0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276, 0.267,
        0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301,
    )
    private val LU = doubleArrayOf(
        -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4, 0.0,
        0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1,
    )
    private val TF = doubleArrayOf(
        78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2, 4.4,
        3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3,
    )
    private const val ONE_K = 17

    /** The model is defined from 20 phon up to 90 (80 at the top frequencies). */
    const val MIN_PHON = 20.0
    const val MAX_PHON = 90.0

    /** Sound pressure level (dB) at the standard's frequency [i] that sounds as loud as [phon] at 1 kHz. */
    fun splAt(i: Int, phon: Double): Double {
        val ln = phon.coerceIn(MIN_PHON, MAX_PHON)
        val af = 4.47e-3 * (10.0.pow(0.025 * ln) - 1.15) + (0.4 * 10.0.pow((TF[i] + LU[i]) / 10.0 - 9.0)).pow(AF[i])
        return 10.0 / AF[i] * log10(af) - LU[i] + 94.0
    }

    val frequencies: DoubleArray get() = F.copyOf()

    /** The contour at [phon] relative to 1 kHz, interpolated on a log scale (held flat past either end). */
    fun shapeDb(frequency: Double, phon: Double): Double {
        val ref = splAt(ONE_K, phon)
        if (frequency <= F.first()) return splAt(0, phon) - ref
        if (frequency >= F.last()) return splAt(F.size - 1, phon) - ref
        var i = 0
        while (F[i + 1] < frequency) i++
        val t = (ln(frequency) - ln(F[i])) / (ln(F[i + 1]) - ln(F[i]))
        return splAt(i, phon) * (1 - t) + splAt(i + 1, phon) * t - ref
    }

    /**
     * How much to raise each frequency (dB) so music at [phonNow] keeps the balance it had at
     * [phonRef], scaled by [amount]. Exactly 0 at 1 kHz, so it never acts as a volume control.
     */
    fun compensationDb(frequency: Double, phonNow: Double, phonRef: Double, amount: Double = 1.0): Double {
        if (phonNow >= phonRef) return 0.0
        return amount * (shapeDb(frequency, phonNow) - shapeDb(frequency, phonRef))
    }

    /** Where listening is now, in phon: the reference at full volume, less however far it's turned down. */
    fun phonAt(refPhon: Double, volumeDb: Double): Double = (refPhon + volumeDb.coerceAtMost(0.0)).coerceIn(MIN_PHON, MAX_PHON)

    /**
     * The correction as a few gentle filters (both engines run filters): shelves and one bell, their
     * gains fitted to the curve. [maxDb] caps the lift, so very low volumes don't ask for absurd bass.
     */
    fun bands(phonNow: Double, phonRef: Double, amount: Double, maxDb: Double = 15.0): List<EqBand> {
        if (phonNow >= phonRef - 0.5 || amount <= 0.0) return emptyList()
        val basis = listOf(
            EqBand(FilterType.LOW_SHELF, 60.0, 0.0, 0.6),
            EqBand(FilterType.LOW_SHELF, 180.0, 0.0, 0.6),
            EqBand(FilterType.LOW_SHELF, 500.0, 0.0, 0.6),
            EqBand(FilterType.PEAK, 3500.0, 0.0, 1.0),
            EqBand(FilterType.HIGH_SHELF, 8000.0, 0.0, 0.6),
            EqBand(FilterType.HIGH_SHELF, 14000.0, 0.0, 0.6),
        )
        val fitted = ToneFit.fit(basis) { f -> compensationDb(f, phonNow, phonRef, amount).coerceAtMost(maxDb) }
        return fitted.filter { abs(it.gainDb) >= 0.05 }
    }
}

/**
 * Fits the gains of a fixed set of filters to a target curve (dB against frequency), by least
 * squares over log-spaced points. A filter's response in dB is nearly proportional to its gain, so
 * a few Gauss-Newton steps land it.
 */
object ToneFit {
    fun fit(
        basis: List<EqBand>,
        points: Int = 48,
        lo: Double = 20.0,
        hi: Double = 16000.0,
        iterations: Int = 4,
        target: (Double) -> Double,
    ): List<EqBand> {
        val n = basis.size
        val freqs = DoubleArray(points) { i -> lo * (hi / lo).pow(i.toDouble() / (points - 1)) }
        val want = DoubleArray(points) { target(freqs[it]) }
        val gains = DoubleArray(n)
        fun response(g: DoubleArray, f: Double): Double {
            var db = 0.0
            for (k in 0 until n) {
                if (g[k] == 0.0) continue
                db += BiquadCoefficients.design(basis[k].copy(gainDb = g[k]), EqResponse.SAMPLE_RATE).magnitudeDb(f, EqResponse.SAMPLE_RATE)
            }
            return db
        }
        repeat(iterations) {
            // Jacobian by a small step on each gain.
            val j = Array(points) { DoubleArray(n) }
            val now = DoubleArray(points) { response(gains, freqs[it]) }
            for (k in 0 until n) {
                val step = gains.copyOf().also { it[k] += 0.5 }
                for (p in 0 until points) j[p][k] = (response(step, freqs[p]) - now[p]) / 0.5
            }
            val a = Array(n) { DoubleArray(n) }
            val b = DoubleArray(n)
            for (p in 0 until points) {
                val r = want[p] - now[p]
                for (k in 0 until n) {
                    b[k] += j[p][k] * r
                    for (m in 0 until n) a[k][m] += j[p][k] * j[p][m]
                }
            }
            // A little damping keeps neighbouring shelves from fighting each other.
            for (k in 0 until n) a[k][k] += 0.02 * points
            val delta = solve(a, b) ?: return@repeat
            for (k in 0 until n) gains[k] = (gains[k] + delta[k]).coerceIn(-EqLimits.MAX_GAIN, EqLimits.MAX_GAIN)
        }
        return basis.mapIndexed { k, band -> band.copy(gainDb = (gains[k] * 100.0).let { kotlin.math.round(it) } / 100.0) }
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> a[i].copyOf() }
        val v = b.copyOf()
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-12) return null
            val tr = m[c]; m[c] = m[piv]; m[piv] = tr
            val tv = v[c]; v[c] = v[piv]; v[piv] = tv
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                for (k in c until n) m[r][k] -= f * m[c][k]
                v[r] -= f * v[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]
            for (k in r + 1 until n) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }
}
