package dev.continuum.fx.lab

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Kira Lab's engine, ported from the Lab's own page (kira-lab.template.html, makeCore) line for
 * line, so a fit here is the fit there: the AutoEq grid, fractional-octave smoothing, the Live
 * model (hall, projection and loudness on the diffuse field), RBJ biquads, the perceptual fitter
 * with its free level, left/right matching, and the files it reads and writes. Nothing here
 * touches Android, so the unit tests run it against the Lab's parity data and fixtures.
 */

/** A filter as the Lab fits and writes it: Equalizer APO's PK, LSC or HSC. */
data class LabFilter(val type: String, val fc: Double, val gain: Double, val q: Double)

/** The reference curves the Live model and the built-in targets are made from. */
class LabRef(
    val df711: DoubleArray,
    val df5128: DoubleArray,
    val d5128iem: DoubleArray?,
    val refs: Map<String, DoubleArray>,
) {
    companion object {
        /** Reads refdata.json: df711, df5128, d5128iem and refs, each 695 values on the grid. */
        @Suppress("UNCHECKED_CAST")
        fun parse(json: String): LabRef {
            val o = MiniJson.parse(json) as Map<String, Any?>
            fun arr(v: Any?): DoubleArray = (v as List<Any?>).map { (it as Number).toDouble() }.toDoubleArray()
            val refs = (o["refs"] as? Map<String, Any?>).orEmpty().mapValues { arr(it.value) }
            return LabRef(arr(o["df711"]), arr(o["df5128"]), o["d5128iem"]?.let { arr(it) }, refs)
        }
    }
}

/** The Live model's settings (Live v2's defaults). */
data class LiveParams(
    /** "711" = 711 IEM or GRAS over-ear, "5128iem" = IEM on a B&K 5128, "5128" = over-ear on a 5128. */
    val rig: String = "711",
    val V: Double = 15000.0,
    val Tmid: Double = 2.0,
    val BR: Double = 1.2,
    val Q: Double = 2.0,
    val r: Double = 20.0,
    val tc: Double = 20.0,
    val rh: Double = 50.0,
    val projPeak: Double = 3.5,
    val projCentre: Double = 3000.0,
    val projSigma: Double = 0.9,
    val livePhon: Double = 90.0,
    val playPhon: Double = 70.0,
)

/** What the fitter is asked to do. [meas], [target] and [spread] are on the grid. */
class FitOptions(
    val meas: DoubleArray,
    val target: DoubleArray,
    val spread: DoubleArray? = null,
    val useSpread: Boolean = false,
    val fs: Double = 48000.0,
    val maxFilters: Int = 8,
    val maxBoost: Double = 6.0,
    val fmin: Double = 20.0,
    val fmax: Double = 16000.0,
    val perceptual: Boolean = true,
    val asym: Boolean = true,
    val robustHF: Boolean = true,
    /** Asked now and then: false stops the fit (it throws CancellationException). */
    val alive: () -> Boolean = { true },
)

class FitStats(val weighted: Double, val rms: Double, val maxAbs: Double, val maxPeak: Double)

class FitResult(
    val filters: List<LabFilter>,
    val preamp: Double,
    /** What the filters do, on the grid. */
    val eq: DoubleArray,
    val before: FitStats,
    val after: FitStats,
    /** Deviation from the target before and after, on the grid (smoothed as the fit saw it). */
    val devBefore: DoubleArray,
    val devAfter: DoubleArray,
    val fs: Double,
    val tried: Int,
    /** The volume offset the fit settled on: only your volume knob, never in the EQ. */
    val level: Double,
)

/** How far apart left and right are, 20 Hz to 10 kHz at 1/6 octave. */
class Mismatch(val max: Double, val signed: Double, val hz: Double, val mid: Double)

/** One side's own correction, on top of the shared filters. */
class ChannelFix(val label: String, val level: Double, val filters: List<LabFilter>)

object Lab {
    const val N = 695
    private const val LN2 = 0.6931471805599453
    fun log2(x: Double): Double = ln(x) / LN2

    private val DL = log2(1.01)
    private val LF0 = log2(20.0)
    val LF = DoubleArray(N) { LF0 + it * DL }
    val F = DoubleArray(N) { 2.0.pow(LF[it]) }

    private fun clamp(v: Double, a: Double, b: Double) = min(b, max(a, v))

    /** Math.round, as JavaScript does it: halves go up. */
    fun jsRound(v: Double): Double = floor(v + 0.5)

    /** Number.toFixed: rounded half up on the exact value, the sign kept for what rounds to 0. */
    fun fixed(v: Double, digits: Int): String {
        val s = BigDecimal(abs(v)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
        return if (v < 0) "-$s" else s
    }

    fun at(y: DoubleArray, hz: Double): Double {
        val t = (log2(hz) - LF0) / DL
        if (t <= 0) return y[0]
        if (t >= N - 1) return y[N - 1]
        val i = floor(t).toInt()
        val u = t - i
        return y[i] * (1 - u) + y[i + 1] * u
    }

    fun norm(y: DoubleArray, hz: Double = 500.0): DoubleArray {
        val o = at(y, hz)
        return DoubleArray(N) { y[it] - o }
    }

    fun add(vararg arrs: DoubleArray): DoubleArray {
        val out = DoubleArray(N)
        for (a in arrs) for (i in 0 until N) out[i] += a[i]
        return out
    }

    fun sub(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(N) { a[it] - b[it] }

    fun resample(fx: DoubleArray, y: DoubleArray): DoubleArray {
        val lx = DoubleArray(fx.size) { log2(fx[it]) }
        val out = DoubleArray(N)
        var j = 0
        for (i in 0 until N) {
            val l = LF[i]
            if (l <= lx[0]) { out[i] = y[0]; continue }
            if (l >= lx[lx.size - 1]) { out[i] = y[y.size - 1]; continue }
            while (j < lx.size - 2 && lx[j + 1] < l) j++
            val u = (l - lx[j]) / (lx[j + 1] - lx[j])
            out[i] = y[j] * (1 - u) + y[j + 1] * u
        }
        return out
    }

    // ---------- fractional-octave smoothing: a Hann window on log frequency ----------
    private const val FN = 8000
    private val FS0 = LF[0] - 2
    private val FS1 = LF[N - 1] + 2
    private val FSTEP = (FS1 - FS0) / (FN - 1)

    private fun edgeSlope(y: DoubleArray, start: Boolean): Double {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until N) {
            if (if (start) LF[i] > LF[0] + 0.5 else LF[i] < LF[N - 1] - 0.5) continue
            n++; sx += LF[i]; sy += y[i]; sxx += LF[i] * LF[i]; sxy += LF[i] * y[i]
        }
        val den = n * sxx - sx * sx
        return if (den != 0.0) (n * sxy - sx * sy) / den else 0.0
    }

    fun smooth(y: DoubleArray, bw: Double): DoubleArray = smooth(y) { bw }

    fun smooth(y: DoubleArray, bwf: (Double) -> Double): DoubleArray {
        val lo = edgeSlope(y, true)
        val hi = edgeSlope(y, false)
        val yf = DoubleArray(FN)
        for (k in 0 until FN) {
            val l = FS0 + k * FSTEP
            yf[k] = when {
                l < LF[0] -> y[0] + lo * (l - LF[0])
                l > LF[N - 1] -> y[N - 1] + hi * (l - LF[N - 1])
                else -> {
                    val t = (l - LF0) / DL
                    val i = min(N - 2, floor(t).toInt())
                    val u = t - i
                    y[i] * (1 - u) + y[i + 1] * u
                }
            }
        }
        val out = DoubleArray(N)
        for (i in 0 until N) {
            val half = bwf(F[i]) / 2
            val a = ceil((LF[i] - half - FS0) / FSTEP - 1e-9).toInt()
            val b = floor((LF[i] + half - FS0) / FSTEP + 1e-9).toInt()
            val m = b - a + 1
            if (m < 1) { out[i] = y[i]; continue }
            var s = 0.0
            var ws = 0.0
            for (k in 0 until m) {
                val w = 0.5 - 0.5 * cos(2 * PI * (k + 1) / (m + 1))
                s += w * yf[a + k]
                ws += w
            }
            out[i] = s / ws
        }
        return out
    }

    // ---------- the Live model: hall, projection and loudness on the diffuse field ----------
    private val E10 = 10 * log10(Math.E)

    /** Air absorption in dB per metre, ISO 9613-1. */
    fun airDbPerM(f: Double, tc: Double = 20.0, rh: Double = 50.0, pa: Double = 101.325): Double {
        val t = tc + 273.15
        val t0 = 293.15
        val t01 = 273.16
        val pr = 101.325
        val psat = 10.0.pow(-6.8346 * (t01 / t).pow(1.261) + 4.6151)
        val h = rh * psat / (pa / pr)
        val frO = (pa / pr) * (24 + 4.04e4 * h * (0.02 + h) / (0.391 + h))
        val frN = (pa / pr) * (t / t0).pow(-0.5) * (9 + 280 * h * exp(-4.170 * ((t / t0).pow(-1.0 / 3) - 1)))
        val z = 0.1068 * exp(-3352 / t) / (frN + f * f / frN)
        val y = (t / t0).pow(-2.5) * (0.01275 * exp(-2239.1 / t) / (frO + f * f / frO) + z)
        return 8.686 * f * f * (1.84e-11 * (pa / pr).pow(-1) * sqrt(t / t0) + y)
    }

    private fun interpLin(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (x <= xs[0]) return ys[0]
        if (x >= xs[xs.size - 1]) return ys[ys.size - 1]
        var j = 0
        while (xs[j + 1] < x) j++
        val u = (x - xs[j]) / (xs[j + 1] - xs[j])
        return ys[j] * (1 - u) + ys[j + 1] * u
    }

    private fun hallDb(p: LiveParams): DoubleArray {
        val m1k = airDbPerM(1000.0, p.tc, p.rh) / E10
        val aS1k = 0.161 * p.V / p.Tmid - 4 * m1k * p.V
        val knots = doubleArrayOf(log2(20.0), log2(250.0), log2(500.0))
        val rts = doubleArrayOf(p.BR * p.Tmid, p.BR * p.Tmid, p.Tmid)
        val e = DoubleArray(N)
        for (i in 0 until N) {
            val f = F[i]
            val airDb = airDbPerM(f, p.tc, p.rh)
            val m = airDb / E10
            val tLow = interpLin(LF[i], knots, rts)
            val aSurf = if (f >= 500) aS1k else 0.161 * p.V / tLow - 4 * m * p.V
            val aTot = aSurf + 4 * m * p.V
            val di = p.projPeak * exp(-0.5 * (log2(f / p.projCentre) / p.projSigma).pow(2))
            val qf = p.Q * 10.0.pow(di / 10)
            val direct = qf / (4 * PI * p.r * p.r) * 10.0.pow(-airDb * p.r / 10)
            e[i] = 10 * log10(direct + 4 / aTot) - di
        }
        return norm(e, 500.0)
    }

    private val ISO_F = doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0)
    private val ISO_AF = doubleArrayOf(0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349, 0.330, 0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242, 0.242, 0.245, 0.254, 0.271, 0.301)
    private val ISO_LU = doubleArrayOf(-31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5, -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1)
    private val ISO_TF = doubleArrayOf(78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3)

    /** Equal-loudness contour (ISO 226:2003) at [phon], in dB SPL. */
    private fun iso226(phon: Double): DoubleArray = DoubleArray(ISO_F.size) { j ->
        val af = 4.47e-3 * (10.0.pow(0.025 * phon) - 1.15) + (0.4 * 10.0.pow((ISO_TF[j] + ISO_LU[j]) / 10 - 9)).pow(ISO_AF[j])
        (10 / ISO_AF[j]) * log10(af) - ISO_LU[j] + 94
    }

    private fun loudnessComp(live: Double, play: Double): DoubleArray {
        val a = iso226(play)
        val b = iso226(live)
        val boost = DoubleArray(a.size) { a[it] - b[it] + (live - play) }
        val lx = DoubleArray(ISO_F.size) { log2(ISO_F[it]) }
        val out = DoubleArray(N) { interpLin(clamp(LF[it], lx[0], lx[lx.size - 1]), lx, boost) }
        return norm(out, 500.0)
    }

    fun liveModel(ref: LabRef, p: LiveParams = LiveParams()): DoubleArray {
        val df = if (p.rig == "5128") ref.df5128 else ref.df711
        val raw = norm(add(df, hallDb(p), loudnessComp(p.livePhon, p.playPhon)))
        val out = norm(smooth(raw, 1.0 / 12))
        val d = ref.d5128iem
        return if (p.rig == "5128iem" && d != null) norm(sub(out, d)) else out
    }

    // ---------- RBJ biquads ----------
    private class Trig(val c1: DoubleArray, val c2: DoubleArray)
    private val trigCache = ConcurrentHashMap<Double, Trig>()
    private fun trig(fs: Double): Trig = trigCache.getOrPut(fs) {
        val c1 = DoubleArray(N)
        val c2 = DoubleArray(N)
        for (i in 0 until N) {
            val w = 2 * PI * F[i] / fs
            c1[i] = cos(w)
            c2[i] = cos(2 * w)
        }
        Trig(c1, c2)
    }

    private fun coeffs(type: String, fc: Double, gain: Double, q: Double, fs: Double): DoubleArray {
        val a = 10.0.pow(gain / 40)
        val w0 = 2 * PI * fc / fs
        val cw = cos(w0)
        val al = sin(w0) / (2 * q)
        val sA = 2 * sqrt(a) * al
        return when (type) {
            "LSC" -> doubleArrayOf(
                a * ((a + 1) - (a - 1) * cw + sA), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sA),
                (a + 1) + (a - 1) * cw + sA, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sA,
            )
            "HSC" -> doubleArrayOf(
                a * ((a + 1) + (a - 1) * cw + sA), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sA),
                (a + 1) - (a - 1) * cw + sA, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sA,
            )
            else -> doubleArrayOf(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a)
        }
    }

    /** One filter's response in dB on the grid, into [out]. */
    fun filterDb(type: String, fc: Double, gain: Double, q: Double, fs: Double, out: DoubleArray = DoubleArray(N)): DoubleArray {
        if (gain == 0.0) { out.fill(0.0); return out }
        val t = trig(fs)
        val c = coeffs(type, fc, gain, q, fs)
        val b0 = c[0]; val b1 = c[1]; val b2 = c[2]; val a0 = c[3]; val a1 = c[4]; val a2 = c[5]
        val nb0 = b0 * b0 + b1 * b1 + b2 * b2
        val nb1 = 2 * (b0 * b1 + b1 * b2)
        val nb2 = 2 * b0 * b2
        val na0 = a0 * a0 + a1 * a1 + a2 * a2
        val na1 = 2 * (a0 * a1 + a1 * a2)
        val na2 = 2 * a0 * a2
        for (i in 0 until N) {
            out[i] = 10 * log10(max(1e-30, nb0 + nb1 * t.c1[i] + nb2 * t.c2[i]) / max(1e-30, na0 + na1 * t.c1[i] + na2 * t.c2[i]))
        }
        return out
    }

    fun filtersDb(filters: List<LabFilter>, fs: Double): DoubleArray {
        val out = DoubleArray(N)
        val tmp = DoubleArray(N)
        for (f in filters) {
            filterDb(f.type, f.fc, f.gain, f.q, fs, tmp)
            for (i in 0 until N) out[i] += tmp[i]
        }
        return out
    }

    // ---------- the fitter ----------
    private fun erbOct(f: Double): Double {
        val e = 24.7 * (4.37 * f / 1000 + 1)
        return if (f - e / 2 <= 1) 1.0 else min(1.0, log2((f + e / 2) / (f - e / 2)))
    }

    private fun eqBw(o: FitOptions): (Double) -> Double = { f ->
        var b = if (o.perceptual) erbOct(f) else 1.0 / 12
        if (o.robustHF && f > 6000) {
            val t = clamp(log2(f / 6000), 0.0, 1.0)
            b = max(b, 1.0 / 6 + t * (1.0 / 2 - 1.0 / 6))
        }
        b
    }

    private fun qMax(fc: Double, o: FitOptions): Double {
        if (!o.robustHF) return 8.0
        if (fc <= 6000) return 6.0
        if (fc >= 10000) return 2.5
        return 6 + (log2(fc / 6000) / log2(10000.0 / 6000)) * (2.5 - 6)
    }

    private fun solve(a: DoubleArray, b: DoubleArray, p: Int): DoubleArray? {
        val m = Array(p) { r -> DoubleArray(p + 1).also { row -> for (c in 0 until p) row[c] = a[r * p + c]; row[p] = b[r] } }
        for (c in 0 until p) {
            var piv = c
            for (r in c + 1 until p) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-14) return null
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            for (r in c + 1 until p) {
                val k = m[r][c] / m[c][c]
                if (k == 0.0) continue
                for (j in c..p) m[r][j] -= k * m[c][j]
            }
        }
        val x = DoubleArray(p)
        for (r in p - 1 downTo 0) {
            var s = m[r][p]
            for (j in r + 1 until p) s -= m[r][j] * x[j]
            x[r] = s / m[r][r]
        }
        return x
    }

    /** A filter while it's being fitted: log2 frequency, gain, log2 Q. */
    private class Fl(val type: String, var lf: Double, var g: Double, var lq: Double) {
        fun copy() = Fl(type, lf, g, lq)
        operator fun get(j: Int) = when (j) { 0 -> lf; 1 -> g; else -> lq }
        operator fun set(j: Int, v: Double) { when (j) { 0 -> lf = v; 1 -> g = v; else -> lq = v } }
    }

    private class Cand(val type: String, val lf: Double, val lq: Double) {
        lateinit var shape: DoubleArray
    }

    private class Out(val type: String, val fc: Double, var gain: Double, val q: Double)

    fun fitEq(o: FitOptions): FitResult {
        val fs = if (o.fs > 0) o.fs else 48000.0
        val cap = o.maxBoost
        val alpha = if (o.asym) 0.55 else 1.0
        val lam = 0.001
        val hubD = 3.0
        var mu = 30.0 / N
        // Huber: errors beyond 3 dB grow linearly, so roll-offs and notches EQ cannot fix don't drag the fit.
        fun hub(r: Double): Double { val a = abs(r); return if (a <= hubD) r else sign(r) * sqrt(hubD * (2 * a - hubD)) }
        fun hubW(r: Double): Double { val a = abs(r); return if (a <= hubD) 1.0 else hubD * (2 * a - hubD) / (a * a) }
        val bwf = eqBw(o)
        val ms = smooth(o.meas, bwf)
        val ts = smooth(o.target, bwf)
        // Level starts matched at 500 Hz, then is solved as a free volume offset alongside the
        // filters, so a target with a deliberate dip at 500 Hz doesn't skew the alignment.
        val d0 = sub(ts, ms)
        val d = DoubleArray(N)
        var off = at(d0, 500.0)
        fun setD() { for (i in 0 until N) d[i] = d0[i] - off }
        setD()
        val spreadS = if (o.spread != null && o.useSpread) smooth(o.spread, 1.0 / 3) else null
        val w = DoubleArray(N)
        var bigW = 0.0
        for (i in 0 until N) {
            val f = F[i]
            if (f < o.fmin || f > o.fmax) continue
            var wi = if (f < 30) 0.6 else 1.0
            if (o.robustHF) {
                if (f > 12000) wi = 0.3 else if (f > 8000) wi = 1 - 0.65 * (log2(f / 8000) / log2(1.5))
            }
            if (spreadS != null) wi /= 1 + (spreadS[i] / 1.5).pow(2)
            w[i] = wi
            bigW += wi
        }
        val lfMin = log2(max(20.0, o.fmin))
        val lfMax = log2(minOf(o.fmax, fs * 0.45, 19500.0))
        fun clampF(fl: Fl) {
            when (fl.type) {
                "LSC" -> { fl.lf = clamp(fl.lf, log2(20.0), log2(400.0)); fl.lq = clamp(fl.lq, log2(0.5), 0.0) }
                "HSC" -> { fl.lf = clamp(fl.lf, log2(2500.0), lfMax); fl.lq = clamp(fl.lq, log2(0.5), 0.0) }
                else -> { fl.lf = clamp(fl.lf, lfMin, lfMax); fl.lq = clamp(fl.lq, log2(0.4), log2(qMax(2.0.pow(fl.lf), o))) }
            }
            fl.g = clamp(fl.g, -12.0, cap)
        }
        fun resp(fl: Fl, out: DoubleArray) = filterDb(fl.type, 2.0.pow(fl.lf), fl.g, 2.0.pow(fl.lq), fs, out)
        fun resp(type: String, lf: Double, g: Double, lq: Double, out: DoubleArray) = filterDb(type, 2.0.pow(lf), g, 2.0.pow(lq), fs, out)
        fun residuals(e: DoubleArray, filters: List<Fl>, out: DoubleArray): DoubleArray {
            var k = 0
            for (i in 0 until N) {
                if (w[i] == 0.0) { out[k++] = 0.0; continue }
                val r = d[i] - e[i]
                out[k++] = sqrt(w[i] * (if (r < 0) 1.0 else alpha) / bigW) * hub(r)
            }
            for (fl in filters) out[k++] = sqrt(lam) * fl.g
            for (i in 0 until N) {
                val ex = e[i] - cap
                out[k++] = if (ex > 0) sqrt(mu) * ex else 0.0
            }
            return out
        }
        fun sumsq(v: DoubleArray): Double { var s = 0.0; for (x in v) s += x * x; return s }
        fun total(r: List<DoubleArray>): DoubleArray { val e = DoubleArray(N); for (x in r) for (i in 0 until N) e[i] += x[i]; return e }
        fun responses(filters: List<Fl>) = filters.map { resp(it, DoubleArray(N)) }
        fun fitPart(e: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until N) {
                if (w[i] == 0.0) continue
                val r = d[i] - e[i]
                val h = hub(r)
                s += w[i] * (if (r < 0) 1.0 else alpha) * h * h
            }
            return s / bigW
        }
        // The best volume offset for the current filters: the loss is convex in it, so a
        // golden-section search finds it.
        fun fitLevel(e: DoubleArray) {
            fun cost(c: Double): Double {
                var s = 0.0
                for (i in 0 until N) {
                    if (w[i] == 0.0) continue
                    val r = d0[i] - c - e[i]
                    val h = hub(r)
                    s += w[i] * (if (r < 0) 1.0 else alpha) * h * h
                }
                return s
            }
            val gr = (sqrt(5.0) - 1) / 2
            var a = off - 6
            var b = off + 6
            var x1 = b - gr * (b - a)
            var x2 = a + gr * (b - a)
            var f1 = cost(x1)
            var f2 = cost(x2)
            for (it in 0 until 36) {
                if (f1 < f2) { b = x2; x2 = x1; f2 = f1; x1 = b - gr * (b - a); f1 = cost(x1) }
                else { a = x1; x1 = x2; f1 = f2; x2 = a + gr * (b - a); f2 = cost(x2) }
            }
            off = (a + b) / 2
            setD()
        }

        fun lm(filters: MutableList<Fl>, iters: Int) {
            val k = filters.size
            val p = k * 3
            if (k == 0) return
            var r = responses(filters)
            var e = total(r)
            val ln = N + k + N
            var rho = residuals(e, filters, DoubleArray(ln))
            var loss = sumsq(rho)
            var damp = 1e-2
            val h = doubleArrayOf(0.02, 0.05, 0.02)
            val jac = DoubleArray(ln * p)
            val tmp = DoubleArray(N)
            val eT = DoubleArray(N)
            val rhoT = DoubleArray(ln)
            var it = 0
            while (it < iters) {
                if (!o.alive()) throw java.util.concurrent.CancellationException("fit stopped")
                for (kk in 0 until k) for (j in 0 until 3) {
                    val fl = filters[kk]
                    val saved = fl[j]
                    fl[j] = saved + h[j]
                    resp(fl, tmp)
                    val rk = r[kk]
                    for (i in 0 until N) eT[i] = e[i] - rk[i] + tmp[i]
                    residuals(eT, filters, rhoT)
                    fl[j] = saved
                    val col = kk * 3 + j
                    for (row in 0 until ln) jac[row * p + col] = (rhoT[row] - rho[row]) / h[j]
                }
                val aa = DoubleArray(p * p)
                val g = DoubleArray(p)
                for (row in 0 until ln) {
                    val base = row * p
                    val rr = rho[row]
                    for (a in 0 until p) {
                        val ja = jac[base + a]
                        if (ja == 0.0) continue
                        g[a] += ja * rr
                        for (b in a until p) aa[a * p + b] += ja * jac[base + b]
                    }
                }
                for (a in 0 until p) for (b in 0 until a) aa[a * p + b] = aa[b * p + a]
                var improved = false
                var tries = 0
                while (tries < 8 && !improved) {
                    val m = aa.copyOf()
                    for (a in 0 until p) m[a * p + a] += damp * (aa[a * p + a] + 1e-6)
                    val delta = solve(m, DoubleArray(p) { -g[it] }, p)
                    if (delta == null) { damp *= 4; tries++; continue }
                    val cand = filters.mapIndexed { kk, fl ->
                        Fl(fl.type, fl.lf + delta[kk * 3], fl.g + delta[kk * 3 + 1], fl.lq + delta[kk * 3 + 2]).also { clampF(it) }
                    }
                    val rc = responses(cand)
                    val ec = total(rc)
                    val rhoC = residuals(ec, cand, DoubleArray(ln))
                    val lc = sumsq(rhoC)
                    if (lc < loss) {
                        val rel = (loss - lc) / loss
                        for (kk in 0 until k) filters[kk] = cand[kk]
                        r = rc; e = ec; rho = rhoC; loss = lc
                        damp = max(1e-7, damp * 0.35)
                        improved = true
                        if (rel < 1e-7) it = iters
                    } else {
                        damp *= 4
                    }
                    tries++
                }
                if (!improved) break
                it++
            }
        }

        // Candidate shapes for placing filters one at a time (unit gain).
        val cands = ArrayList<Cand>()
        var l = lfMin + 1.0 / 12
        while (l <= lfMax - 1.0 / 12 + 1e-9) {
            for (q in doubleArrayOf(0.7, 1.4, 2.8, 5.0)) if (q <= qMax(2.0.pow(l), o) + 1e-9) cands += Cand("PK", l, log2(q))
            l += 1.0 / 6
        }
        for (fc in doubleArrayOf(40.0, 60.0, 80.0, 105.0, 140.0, 200.0, 300.0)) if (fc >= o.fmin * 0.8) cands += Cand("LSC", log2(fc), log2(0.71))
        for (fc in doubleArrayOf(3000.0, 4500.0, 6000.0, 8000.0, 10000.0, 12000.0)) if (fc <= o.fmax) cands += Cand("HSC", log2(fc), log2(0.71))
        for (c in cands) c.shape = resp(c.type, c.lf, 1.0, c.lq, DoubleArray(N))

        val filters = ArrayList<Fl>()
        var e = DoubleArray(N)
        // Start from the best level with no filters, so a cuts-only fit isn't held at 500 Hz.
        fitLevel(e)
        class Step(val filters: List<Fl>, val fit: Double)
        val history = ArrayList<Step>()
        for (k in 0 until o.maxFilters) {
            var best: Cand? = null
            var bestG = 0.0
            var bestImp = 1e-7
            val r = DoubleArray(N)
            val aw = DoubleArray(N)
            for (i in 0 until N) {
                r[i] = d[i] - e[i]
                aw[i] = w[i] * (if (r[i] < 0) 1.0 else alpha) * hubW(r[i])
            }
            for (c in cands) {
                var num = 0.0
                var den = 0.0
                val s = c.shape
                for (i in 0 until N) {
                    if (aw[i] == 0.0) continue
                    num += aw[i] * r[i] * s[i]
                    den += aw[i] * s[i] * s[i]
                }
                if (den <= 0) continue
                val g = clamp(num / den, -12.0, cap)
                val imp = g * (2 * num - g * den) / bigW - lam * g * g
                if (imp > bestImp) { bestImp = imp; best = c; bestG = g }
            }
            val b = best ?: break
            val fl = Fl(b.type, b.lf, bestG, b.lq)
            clampF(fl)
            filters += fl
            lm(filters, 14)
            e = total(responses(filters))
            fitLevel(e)
            lm(filters, 6)
            e = total(responses(filters))
            history += Step(filters.map { it.copy() }, fitPart(e))
        }

        var chosen: MutableList<Fl> = ArrayList()
        if (history.isNotEmpty()) {
            val best = sqrt(history.minOf { it.fit })
            val tol = max(0.05, 0.05 * best)
            var pick = history[history.size - 1].filters
            for (h in history) if (sqrt(h.fit) <= best + tol) { pick = h.filters; break }
            chosen = pick.map { it.copy() }.toMutableList()
            lm(chosen, 50)
            fitLevel(total(responses(chosen)))
            lm(chosen, 20)
            chosen = chosen.filter { abs(it.g) >= 0.25 }.toMutableList()
            lm(chosen, 30)
            fitLevel(total(responses(chosen)))
            lm(chosen, 20)
            for (pass in 0 until 3) {
                val ec = total(responses(chosen))
                var peak = Double.NEGATIVE_INFINITY
                for (i in 0 until N) peak = max(peak, ec[i])
                if (peak <= cap + 0.05) break
                mu *= 10
                lm(chosen, 30)
            }
        }
        val out = chosen
            .map { Out(it.type, jsRound(2.0.pow(it.lf)), jsRound(it.g * 10) / 10, jsRound(2.0.pow(it.lq) * 100) / 100) }
            .filter { it.gain != 0.0 }
            .sortedBy { it.fc }
        fun outFilters() = out.map { LabFilter(it.type, it.fc, it.gain, it.q) }
        var eq = filtersDb(outFilters(), fs)
        // Rounding can nudge the total past the boost limit: trim the filter that causes it.
        for (pass in 0 until 6) {
            var mi = 0
            for (i in 1 until N) if (eq[i] > eq[mi]) mi = i
            val excess = eq[mi] - cap
            if (excess <= 0.005) break
            var worst: Out? = null
            var wv = 0.0
            for (f in out) {
                val v = filterDb(f.type, f.fc, f.gain, f.q, fs)[mi]
                if (v > wv) { wv = v; worst = f }
            }
            val wf = worst ?: break
            wf.gain = jsRound((wf.gain - max(0.1, excess)) * 10) / 10
            eq = filtersDb(outFilters(), fs)
        }
        var mx = 0.0
        for (i in 0 until N) mx = max(mx, eq[i])
        val preamp = if (mx > 0) -ceil(mx * 10) / 10 else 0.0
        fun stats(eqr: DoubleArray): FitStats {
            var sse = 0.0
            var sw = 0.0
            var ss = 0.0
            var n = 0
            var maxAbs = 0.0
            var maxPeak = Double.NEGATIVE_INFINITY
            for (i in 0 until N) {
                val dev = eqr[i] - d[i]
                if (w[i] != 0.0) { sse += w[i] * (if (dev > 0) 1.0 else alpha) * dev * dev; sw += w[i] }
                if (F[i] >= 20 && F[i] <= 10000) {
                    ss += dev * dev; n++
                    maxAbs = max(maxAbs, abs(dev))
                    maxPeak = max(maxPeak, dev)
                }
            }
            return FitStats(sqrt(sse / sw), sqrt(ss / n), maxAbs, max(0.0, maxPeak))
        }
        val before = stats(DoubleArray(N))
        val after = stats(eq)
        val devBefore = DoubleArray(N) { -d[it] }
        val devAfter = DoubleArray(N) { eq[it] - d[it] }
        return FitResult(outFilters(), preamp, eq, before, after, devBefore, devAfter, fs, history.size, off)
    }

    // ---------- text formats ----------

    /** Equalizer APO text; [chans] adds a section for each side after the shared filters. */
    fun apoText(filters: List<LabFilter>, preamp: Double, chans: List<ChannelFix>? = null): String {
        val lines = ArrayList<String>()
        lines += "Preamp: ${fixed(preamp, 1)} dB"
        var n = 0
        fun line(f: LabFilter) = "Filter ${++n}: ON ${f.type} Fc ${jsRound(f.fc).toLong()} Hz Gain ${fixed(f.gain, 1)} dB Q ${fixed(f.q, 2)}"
        filters.forEach { lines += line(it) }
        if (!chans.isNullOrEmpty()) {
            for (c in chans) {
                lines += "Channel: ${c.label}"
                if (abs(c.level) >= 0.05) lines += "Preamp: ${fixed(c.level, 1)} dB"
                c.filters.forEach { lines += line(it) }
            }
            lines += "Channel: all"
        }
        return lines.joinToString("\n") + "\n"
    }

    /** One GraphicEQ line (Wavelet, Equalizer APO): 127 points, 20 Hz to 20 kHz. */
    fun graphicEqText(eq: DoubleArray, preamp: Double): String {
        val seen = HashSet<Long>()
        val parts = ArrayList<String>()
        for (k in 0 until 127) {
            val hz = jsRound(20 * 1000.0.pow(k / 126.0)).toLong()
            if (!seen.add(hz)) continue
            parts += "$hz ${fixed(at(eq, hz.toDouble()) + preamp, 1)}"
        }
        return "GraphicEQ: " + parts.joinToString("; ") + "\n"
    }

    fun targetTxt(y: DoubleArray): String = buildString {
        for (i in 0 until N) append(fixed(F[i], 2)).append('\t').append(fixed(y[i], 2)).append('\n')
    }

    fun targetCsv(y: DoubleArray): String = buildString {
        append("frequency,raw\n")
        for (i in 0 until N) append(fixed(F[i], 2)).append(',').append(fixed(y[i], 2)).append('\n')
    }

    // ---------- reading curves ----------
    class Curve(val f: DoubleArray, val y: DoubleArray)

    private val SPLIT_FIRST = Regex("[\t; ]+")
    private val SPLIT_ANY = Regex("[\\s\\u00A0\\uFEFF,;]+")
    private val DECIMAL_COMMA = Regex("^-?\\d+,\\d+$")
    private val NUMBER = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** Number(s), as JavaScript reads a plain decimal. */
    private fun jsNumber(s: String): Double? = if (NUMBER.matches(s)) s.toDoubleOrNull() else null

    /** REW, squig.link and AutoEq text: a frequency and a level on each line, sorted and deduplicated. */
    fun parseCurveText(text: String): Curve {
        val fx = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (raw in text.removePrefix("﻿").split(Regex("\r?\n"))) {
            val line = raw.trim()
            if (line.isEmpty() || "*#;/".indexOf(line[0]) >= 0) continue
            var parts = line.split(SPLIT_FIRST).filter { it.isNotEmpty() }
            val fv: Double?
            val yv: Double?
            if (parts.size >= 2 && DECIMAL_COMMA.matches(parts[0]) && DECIMAL_COMMA.matches(parts[1])) {
                fv = parts[0].replace(",", ".").toDoubleOrNull()
                yv = parts[1].replace(",", ".").toDoubleOrNull()
            } else {
                parts = line.split(SPLIT_ANY).filter { it.isNotEmpty() }
                if (parts.size < 2) continue
                fv = jsNumber(parts[0])
                yv = jsNumber(parts[1])
            }
            if (fv == null || yv == null || !fv.isFinite() || !yv.isFinite() || fv <= 0) continue
            fx += fv
            ys += yv
        }
        val idx = fx.indices.sortedBy { fx[it] }
        val f2 = ArrayList<Double>()
        val y2 = ArrayList<Double>()
        for (i in idx) {
            if (f2.isNotEmpty() && fx[i] <= f2[f2.size - 1]) { y2[y2.size - 1] = ys[i]; continue }
            f2 += fx[i]
            y2 += ys[i]
        }
        return Curve(f2.toDoubleArray(), y2.toDoubleArray())
    }

    /** A file's name without its L or R: "MEST L.txt" is MEST's left channel. */
    fun splitChannelName(filename: String): Pair<String, String> {
        val base = filename.replace(Regex("\\.[^.]+$"), "")
        val m = Regex("^(.*?)[\\s_\\-.(\\[]+(L|R|left|right)[\\s_\\-.)\\]]*$", RegexOption.IGNORE_CASE).find(base)
        if (m != null && m.groupValues[1].trim().isNotEmpty()) {
            val ch = if (m.groupValues[2].lowercase().startsWith("l")) "L" else "R"
            return m.groupValues[1].replace(Regex("[\\s_\\-.(\\[]+$"), "").trim() to ch
        }
        return base.replace("_", " ").trim() to "avg"
    }

    fun powerAverage(chs: List<DoubleArray>): DoubleArray {
        if (chs.size == 1) return chs[0].copyOf()
        return DoubleArray(N) { i ->
            var s = 0.0
            for (c in chs) s += 10.0.pow(c[i] / 10)
            10 * log10(s / chs.size)
        }
    }

    fun spread(chs: List<DoubleArray>): DoubleArray? {
        if (chs.size < 2) return null
        return DoubleArray(N) { i ->
            var m = 0.0
            for (c in chs) m += c[i]
            m /= chs.size
            var v = 0.0
            for (c in chs) v += (c[i] - m) * (c[i] - m)
            sqrt(v / chs.size)
        }
    }

    val KEY_POINTS = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 1000.0, 4000.0, 6000.0, 8000.0, 10000.0, 16000.0)

    /** The curve at the key frequencies, and its ear-gain peak (1.5 to 5 kHz) as (Hz, dB). */
    fun keyValues(y: DoubleArray): Pair<List<Pair<Double, Double>>, Pair<Double, Double>> {
        var pk = Double.NEGATIVE_INFINITY
        var pf = 0.0
        for (i in 0 until N) if (F[i] > 1500 && F[i] < 5000 && y[i] > pk) { pk = y[i]; pf = F[i] }
        return KEY_POINTS.map { it to at(y, it) } to (pf to pk)
    }

    // ---------- Live v2.1: the listener's own carve (Nov 2026) ----------
    // Gives every range its own space: lift where things define themselves, cut where they mask.
    // Rig-independent, so it applies to every Live v2 version.
    val CARVE = listOf(
        LabFilter("PK", 44.0, 1.1, 1.3),   // slam: kick and bass fundamentals
        LabFilter("PK", 111.0, -1.9, 1.95), // boom and overhang that mask the low mids
        LabFilter("PK", 288.0, 1.0, 1.42),  // instrument bodies, chest voice
        LabFilter("PK", 503.0, -1.3, 1.03), // the crowded box/honk octave
        LabFilter("LSC", 24.0, -0.7, 2.0),  // no energy spent below the music
    )
    private val carveCache = ConcurrentHashMap<Double, DoubleArray>()

    fun carveDb(scale: Double): DoubleArray {
        val k = jsRound(scale * 1000) / 1000
        return carveCache.getOrPut(k) { filtersDb(CARVE.map { it.copy(gain = it.gain * k) }, 192000.0) }
    }

    // ---------- left and right ----------

    /** The largest L−R difference, 20 Hz to 10 kHz, at 1/6 octave (above 10 kHz it's mostly insertion depth). */
    fun lrMismatch(a: DoubleArray, b: DoubleArray): Mismatch {
        val d = smooth(sub(a, b), 1.0 / 6)
        var mx = 0.0
        var hz = 1000.0
        val mid = ArrayList<Double>()
        for (i in 0 until N) {
            if (F[i] < 20 || F[i] > 10000) continue
            if (abs(d[i]) > abs(mx)) { mx = d[i]; hz = F[i] }
            if (F[i] >= 200 && F[i] <= 2000) mid += d[i]
        }
        mid.sort()
        return Mismatch(abs(mx), mx, hz, if (mid.isEmpty()) 0.0 else mid[mid.size / 2])
    }
}

/** The Lab's own JSON reader: just enough for refdata and the tests, no Android needed. */
object MiniJson {
    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "Trailing text at ${p.i}" }
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "Unexpected end" }
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> num()
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)) { "Expected $w at $i" }; i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                require(s[i] == ':') { "Expected : at $i" }
                i++
                m[k] = value()
                ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}') { "Expected } at $i" }
                i++
                return m
            }
        }
        fun arr(): List<Any?> {
            val a = ArrayList<Any?>()
            i++
            ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a += value()
                ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']') { "Expected ] at $i" }
                i++
                return a
            }
        }
        fun str(): String {
            require(s[i] == '"') { "Expected string at $i" }
            i++
            val b = StringBuilder()
            while (s[i] != '"') {
                val c = s[i++]
                if (c != '\\') { b.append(c); continue }
                when (val e = s[i++]) {
                    'n' -> b.append('\n')
                    't' -> b.append('\t')
                    'r' -> b.append('\r')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
            }
            i++
            return b.toString()
        }
        fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
    }
}
