package dev.continuum.fx.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A frequency response: how loud each frequency comes out, in dB, as measured (an earphone on a
 * coupler) or wished for (a target). Read from the text files measurement tools and squig.link
 * write: a frequency and a level on each line, split by tabs, spaces, commas or semicolons, with
 * any header or comment lines skipped.
 */
data class Curve(val name: String, val frequency: DoubleArray, val db: DoubleArray) {
    val size get() = frequency.size

    /** The level at [f], read between the points on a log scale, held flat past the ends. */
    fun at(f: Double): Double {
        if (f <= frequency.first()) return db.first()
        if (f >= frequency.last()) return db.last()
        var lo = 0
        var hi = frequency.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (frequency[mid] <= f) lo = mid else hi = mid
        }
        val t = ln(f / frequency[lo]) / ln(frequency[hi] / frequency[lo])
        return db[lo] + (db[hi] - db[lo]) * t
    }

    override fun equals(other: Any?) = other is Curve && other.name == name &&
        other.frequency.contentEquals(frequency) && other.db.contentEquals(db)

    override fun hashCode() = name.hashCode() * 31 + frequency.contentHashCode()

    companion object {
        private val number = Regex("""^[+-]?(\d+([.,]\d*)?|[.,]\d+)([eE][+-]?\d+)?$""")

        /** Reads [text], or says why it isn't a frequency response. */
        fun parse(text: String, name: String): Result<Curve> = runCatching {
            val points = ArrayList<Pair<Double, Double>>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '#' || line[0] == '*' || line.startsWith("//")) continue
                val semicolons = ';' in line
                val parts = when {
                    semicolons -> line.split(';')
                    '\t' in line -> line.split('\t')
                    ',' in line && ' ' !in line.trim() -> line.split(',')
                    ',' in line -> line.split(',', ' ').filter { it.isNotBlank() }
                    else -> line.split(Regex("\\s+"))
                }.map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size < 2 || !number.matches(parts[0]) || !number.matches(parts[1])) continue
                // A decimal comma only where commas can't be the separator.
                fun value(s: String) = (if (semicolons || '\t' in line) s.replace(',', '.') else s).toDouble()
                val f = value(parts[0])
                val d = value(parts[1])
                if (f > 0 && f < 100000 && d.isFinite()) points += f to d
            }
            require(points.size >= 10) { "That file doesn't look like a frequency response: it needs a frequency and a level on each line." }
            val sorted = points.sortedBy { it.first }.distinctBy { it.first }
            require(sorted.first().first < 200 && sorted.last().first > 5000) {
                "That response only covers ${sorted.first().first.roundToInt()} to ${sorted.last().first.roundToInt()} Hz: it needs most of the range, about 20 Hz to 20 kHz."
            }
            val full = Curve(
                cleanName(name),
                DoubleArray(sorted.size) { sorted[it].first },
                DoubleArray(sorted.size) { sorted[it].second },
            )
            // A full-resolution export has tens of thousands of points; 1/96 octave is plenty.
            if (full.size <= 1200) full else {
                val lo = full.frequency.first()
                val octaves = log2(full.frequency.last() / lo)
                val count = (octaves * 96).toInt() + 1
                val f = DoubleArray(count) { lo * 2.0.pow(it / 96.0) }
                Curve(full.name, f, DoubleArray(count) { full.at(f[it]) })
            }
        }

        private fun cleanName(raw: String): String =
            raw.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Response" }.take(40)
    }
}

/**
 * Makes a parametric EQ that turns one response into another: earphones, as measured, into the
 * target you want them to sound like. Done the way AutoEQ does it: the difference is smoothed
 * (more in the treble, where coupler measurements stop being trustworthy and every fit differs),
 * centred, limited, and then filters are placed where it's largest and all tuned together until
 * the EQ follows the difference as closely as it can.
 */
object EqFit {
    data class Options(
        /** How many filters, shelves included. */
        val filters: Int = 10,
        /** The most any frequency is lifted: boosts eat headroom and distort sooner. */
        val maxBoostDb: Double = 6.0,
        /** Above this the difference counts for less and less: coupler treble isn't reliable. */
        val fitTo: Double = 10000.0,
    )

    class Result(
        val profile: EqProfile,
        /** How far off the earphones were, and are with the EQ, in dB (RMS, 20 Hz to [Options.fitTo]). */
        val errorBefore: Double,
        val errorAfter: Double,
        /** The grid, the (smoothed, centred, limited) difference aimed for, and what the EQ does. */
        val frequency: DoubleArray,
        val wanted: DoubleArray,
        val response: DoubleArray,
    )

    private const val POINTS = 240 // 1/24 octave from 20 Hz to 20 kHz
    private const val SR = EqResponse.SAMPLE_RATE

    fun grid(): DoubleArray = DoubleArray(POINTS) { 20.0 * 1000.0.pow(it / (POINTS - 1.0)) }

    fun fit(measurement: Curve, target: Curve, options: Options = Options(), name: String? = null): Result {
        val f = grid()
        val n = f.size
        val raw = DoubleArray(n) { target.at(f[it]) - measurement.at(f[it]) }
        val wanted = smooth(f, raw)
        // Centred on the middle of the range, so the EQ moves the balance, not the volume.
        val mids = f.indices.filter { f[it] in 200.0..4000.0 }
        val offset = mids.sumOf { wanted[it] } / mids.size
        for (i in 0 until n) wanted[i] = (wanted[i] - offset).coerceIn(-15.0, options.maxBoostDb)
        val weight = DoubleArray(n) { i ->
            when {
                f[i] < 25.0 -> 0.5
                f[i] <= options.fitTo -> 1.0
                else -> max(0.03, (options.fitTo / f[i]).pow(4))
            }
        }

        // Start: a low shelf, a high shelf, then peaks where the difference is largest.
        val bands = ArrayList<EqBand>()
        val bass = f.indices.filter { f[it] in 20.0..60.0 }.map { wanted[it] }.average()
        bands += EqBand(FilterType.LOW_SHELF, 105.0, bass.coerceIn(-12.0, options.maxBoostDb), 0.7)
        val hsAt = min(10000.0, options.fitTo)
        val top = f.indices.filter { f[it] in (hsAt / 1.5)..hsAt }.map { wanted[it] }.average()
        bands += EqBand(FilterType.HIGH_SHELF, hsAt, top.coerceIn(-12.0, options.maxBoostDb), 0.7)
        val resp = Array(options.filters) { DoubleArray(n) }
        fun respond(i: Int) {
            val c = BiquadCoefficients.design(bands[i], SR)
            for (k in 0 until n) resp[i][k] = c.magnitudeDb(f[k], SR)
        }
        respond(0)
        respond(1)
        val residual = DoubleArray(n)
        fun updateResidual() {
            for (k in 0 until n) {
                var s = 0.0
                for (b in bands.indices) s += resp[b][k]
                residual[k] = wanted[k] - s
            }
        }
        while (bands.size < options.filters) {
            updateResidual()
            var best = -1
            var bestV = 0.0
            for (k in 0 until n) {
                if (f[k] > options.fitTo * 1.2) break
                val v = abs(residual[k]) * weight[k]
                if (v > bestV) {
                    bestV = v
                    best = k
                }
            }
            if (best < 0 || bestV < 0.3) break
            val g = residual[best]
            var lo = best
            var hi = best
            while (lo > 0 && abs(residual[lo - 1]) > abs(g) / 2 && residual[lo - 1] * g > 0) lo--
            while (hi < n - 1 && abs(residual[hi + 1]) > abs(g) / 2 && residual[hi + 1] * g > 0) hi++
            val octaves = max(1.0 / 6, (hi - lo + 1) / 24.0)
            val q = (sqrt(2.0.pow(octaves)) / (2.0.pow(octaves) - 1)).coerceIn(0.4, 5.0)
            bands += EqBand(FilterType.PEAK, f[best], g.coerceIn(-12.0, 12.0), q)
            respond(bands.size - 1)
        }

        tune(bands, resp, f, wanted, weight, options)
        updateResidual()

        // Tidy: rounded as the editor shows them, tiny filters dropped, in frequency order.
        val tidy = bands
            .map { b -> b.copy(frequency = b.frequency.roundToInt().toDouble(), gainDb = (b.gainDb * 10).roundToInt() / 10.0, q = (b.q * 100).roundToInt() / 100.0) }
            .filter { abs(it.gainDb) >= 0.2 }
            .sortedBy { it.frequency }
        val draft = EqProfile(name = name ?: "${measurement.name} → ${target.name}", bands = tidy)
        val response = DoubleArray(n) { EqResponse.gainAt(draft, f[it]) }
        val profile = draft.copy(preampDb = EqResponse.suggestedPreamp(draft))

        fun rms(err: (Int) -> Double): Double {
            var s = 0.0
            var c = 0
            for (k in 0 until n) if (f[k] <= options.fitTo) {
                s += err(k) * err(k)
                c++
            }
            return sqrt(s / c)
        }
        return Result(
            profile = profile,
            errorBefore = rms { wanted[it] },
            errorAfter = rms { wanted[it] - response[it] },
            frequency = f,
            wanted = wanted,
            response = response,
        )
    }

    /**
     * Smoothed by 1/12 octave up to 4 kHz, widening to an octave by 16 kHz: fine detail in the
     * treble is the coupler and the fit, not the earphones.
     */
    private fun smooth(f: DoubleArray, y: DoubleArray): DoubleArray {
        val n = f.size
        return DoubleArray(n) { i ->
            val width = when {
                f[i] <= 4000.0 -> 1.0 / 12
                f[i] >= 16000.0 -> 1.0
                else -> {
                    val t = log2(f[i] / 4000.0) / 2.0
                    1.0 / 12 + (1.0 - 1.0 / 12) * t
                }
            }
            val half = (width / 2 * 24).roundToInt()
            var s = 0.0
            var c = 0
            for (k in max(0, i - half)..min(n - 1, i + half)) {
                s += y[k]
                c++
            }
            s / c
        }
    }

    /**
     * Tunes every filter's frequency, gain and Q together (Levenberg-Marquardt on the weighted
     * difference), within sensible bounds.
     */
    private fun tune(bands: MutableList<EqBand>, resp: Array<DoubleArray>, f: DoubleArray, wanted: DoubleArray, weight: DoubleArray, options: Options) {
        val n = f.size
        val m = bands.size
        // Parameters: log2 frequency, gain, log2 Q for peaks; log2 frequency and gain for shelves.
        fun paramsOf(b: EqBand) = if (b.type == FilterType.PEAK) 3 else 2
        val count = bands.sumOf { paramsOf(it) }
        val sw = DoubleArray(n) { sqrt(weight[it]) }

        fun bandFrom(b: EqBand, x: DoubleArray, at: Int): EqBand {
            val lo = when (b.type) {
                FilterType.LOW_SHELF -> 30.0
                FilterType.HIGH_SHELF -> 3000.0
                else -> 20.0
            }
            val hi = when (b.type) {
                FilterType.LOW_SHELF -> 300.0
                FilterType.HIGH_SHELF -> 16000.0
                else -> min(18000.0, options.fitTo * 1.3)
            }
            val freq = 2.0.pow(x[at]).coerceIn(lo, hi)
            val gain = x[at + 1].coerceIn(-15.0, 12.0)
            val q = if (b.type == FilterType.PEAK) 2.0.pow(x[at + 2]).coerceIn(0.3, 6.0) else b.q
            return b.copy(frequency = freq, gainDb = gain, q = q)
        }

        fun response(b: EqBand, out: DoubleArray) {
            val c = BiquadCoefficients.design(b, SR)
            for (k in 0 until n) out[k] = c.magnitudeDb(f[k], SR)
        }

        val x = DoubleArray(count)
        val offsets = IntArray(m)
        run {
            var at = 0
            for ((i, b) in bands.withIndex()) {
                offsets[i] = at
                x[at] = log2(b.frequency)
                x[at + 1] = b.gainDb
                if (b.type == FilterType.PEAK) x[at + 2] = log2(b.q)
                at += paramsOf(b)
            }
        }

        val total = DoubleArray(n)
        fun cost(): Double {
            total.fill(0.0)
            for (b in 0 until m) for (k in 0 until n) total[k] += resp[b][k]
            var s = 0.0
            for (k in 0 until n) {
                val r = (wanted[k] - total[k]) * sw[k]
                s += r * r
            }
            return s
        }

        var mu = 1e-2
        var current = cost()
        val jac = Array(count) { DoubleArray(n) }
        val tmp = DoubleArray(n)
        val r = DoubleArray(n)
        repeat(80) {
            for (k in 0 until n) r[k] = (wanted[k] - total[k]) * sw[k]
            // The Jacobian, by finite differences: each parameter only moves its own filter.
            for (i in 0 until m) {
                val b = bands[i]
                for (p in 0 until paramsOf(b)) {
                    val j = offsets[i] + p
                    val h = if (p == 1) 0.05 else 0.01
                    x[j] += h
                    response(bandFrom(b, x, offsets[i]), tmp)
                    x[j] -= h
                    for (k in 0 until n) jac[j][k] = -(tmp[k] - resp[i][k]) / h * sw[k]
                }
            }
            val a = Array(count) { DoubleArray(count) }
            val g = DoubleArray(count)
            for (p in 0 until count) {
                for (q in p until count) {
                    var s = 0.0
                    for (k in 0 until n) s += jac[p][k] * jac[q][k]
                    a[p][q] = s
                    a[q][p] = s
                }
                var s = 0.0
                for (k in 0 until n) s += jac[p][k] * r[k]
                g[p] = -s
            }
            var improved = false
            for (attempt in 0 until 6) {
                val sys = Array(count) { p -> DoubleArray(count) { q -> a[p][q] + if (p == q) mu * (1 + a[p][p]) else 0.0 } }
                val step = solve(sys, g.copyOf()) ?: break
                val trial = DoubleArray(count) { x[it] + step[it] }
                val saved = Array(m) { resp[it].copyOf() }
                val savedBands = bands.toList()
                for (i in 0 until m) {
                    bands[i] = bandFrom(bands[i], trial, offsets[i])
                    response(bands[i], resp[i])
                }
                val c = cost()
                if (c < current) {
                    // Keep the clamped values, so the parameters and the filters agree.
                    for (i in 0 until m) {
                        val b = bands[i]
                        trial[offsets[i]] = log2(b.frequency)
                        trial[offsets[i] + 1] = b.gainDb
                        if (b.type == FilterType.PEAK) trial[offsets[i] + 2] = log2(b.q)
                    }
                    System.arraycopy(trial, 0, x, 0, count)
                    val gain = current - c
                    current = c
                    mu = max(1e-6, mu / 3)
                    improved = gain > 1e-6 * current
                    break
                } else {
                    for (i in 0 until m) {
                        bands[i] = savedBands[i]
                        System.arraycopy(saved[i], 0, resp[i], 0, n)
                    }
                    cost()
                    mu *= 4
                }
            }
            if (!improved) return
        }
    }

    /** Solves a small linear system by Gaussian elimination with pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (c in 0 until n) {
            var piv = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
            if (abs(a[piv][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            val tb = b[c]; b[c] = b[piv]; b[piv] = tb
            for (r in c + 1 until n) {
                val k = a[r][c] / a[c][c]
                if (k == 0.0) continue
                for (q in c until n) a[r][q] -= k * a[c][q]
                b[r] -= k * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (q in r + 1 until n) s -= a[r][q] * x[q]
            x[r] = s / a[r][r]
        }
        return x
    }
}
