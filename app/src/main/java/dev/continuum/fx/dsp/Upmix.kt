package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Opens a stereo mix up for surround speakers. Android folds 5.1 and 7.1 down to stereo before
 * anything can capture it, so the surround layouts work from the stereo: in each narrow band of
 * frequencies, moment to moment, what's the same in both channels (a voice, an instrument) is
 * a source with a place in the mix, and what differs (reverb, crowd, the surrounds that were
 * folded in) is the space around it.
 *
 * Each source goes to the front speakers either side of where it sat in the stereo picture,
 * with its power kept; the space goes partly to the fronts and mostly to the surrounds on its
 * own side. Done on 50%-overlapped frames of 2 x [hop] samples, so it delays by [hop].
 */
internal class Upmix(private val hop: Int, sampleRate: Double) {
    private val n = 2 * hop
    private val fft = RealFft(n)
    private val bins = fft.bins
    private val window = FloatArray(n) { sin(PI * it / n).toFloat() }
    private val smooth = exp(-hop / (sampleRate * 0.04)).toFloat()

    private val historyL = FloatArray(n)
    private val historyR = FloatArray(n)
    private val frame = FloatArray(n)
    private val lr = FloatArray(bins)
    private val li = FloatArray(bins)
    private val rr = FloatArray(bins)
    private val ri = FloatArray(bins)
    private val pll = FloatArray(bins)
    private val prr = FloatArray(bins)
    private val plrRe = FloatArray(bins)

    private var speakers = 0
    private var outRe = Array(0) { FloatArray(0) }
    private var outIm = Array(0) { FloatArray(0) }
    private var overlap = Array(0) { FloatArray(0) }
    private val time = FloatArray(n)

    // The layout, in the form the per-band work needs.
    private var frontIdx = IntArray(0)
    private var frontAngle = FloatArray(0)
    private var side = IntArray(0)
    private var surroundLeft = IntArray(0)
    private var surroundRight = IntArray(0)
    private var frontLeft = -1
    private var frontRight = -1
    private var halfWidth = 30f

    /** How much of the space stays in the front speakers. */
    private val frontSpace = 0.35f

    fun configure(layout: List<Speaker>, width: Float) {
        speakers = layout.size
        if (outRe.size != speakers) {
            outRe = Array(speakers) { FloatArray(bins) }
            outIm = Array(speakers) { FloatArray(bins) }
            overlap = Array(speakers) { FloatArray(n) }
        }
        val fronts = layout.withIndex().filter { !it.value.role.surround }.sortedBy { it.value.angle }
        frontIdx = fronts.map { it.index }.toIntArray()
        frontAngle = fronts.map { it.value.angle }.toFloatArray()
        side = IntArray(speakers) { i ->
            val a = layout[i].angle
            when {
                a < -1f -> -1
                a > 1f -> 1
                else -> 0
            }
        }
        surroundLeft = layout.withIndex().filter { it.value.role.surround && it.value.angle < 0 }.map { it.index }.toIntArray()
        surroundRight = layout.withIndex().filter { it.value.role.surround && it.value.angle >= 0 }.map { it.index }.toIntArray()
        frontLeft = layout.indexOfFirst { it.role == SpeakerRole.FrontLeft }
        frontRight = layout.indexOfFirst { it.role == SpeakerRole.FrontRight }
        val l = layout.firstOrNull { it.role == SpeakerRole.FrontLeft }?.angle ?: -30f
        val r = layout.firstOrNull { it.role == SpeakerRole.FrontRight }?.angle ?: 30f
        halfWidth = (r - l) / 2f * width
    }

    fun reset() {
        historyL.fill(0f)
        historyR.fill(0f)
        pll.fill(0f)
        prr.fill(0f)
        plrRe.fill(0f)
        for (o in overlap) o.fill(0f)
    }

    /** [hop] samples of each channel in; [hop] samples per speaker out (into [out]). */
    fun process(l: FloatArray, r: FloatArray, out: Array<FloatArray>) {
        System.arraycopy(historyL, hop, historyL, 0, n - hop)
        System.arraycopy(historyR, hop, historyR, 0, n - hop)
        System.arraycopy(l, 0, historyL, n - hop, hop)
        System.arraycopy(r, 0, historyR, n - hop, hop)
        for (i in 0 until n) frame[i] = historyL[i] * window[i]
        fft.forward(frame, lr, li)
        for (i in 0 until n) frame[i] = historyR[i] * window[i]
        fft.forward(frame, rr, ri)

        for (s in 0 until speakers) {
            outRe[s].fill(0f)
            outIm[s].fill(0f)
        }
        val a = smooth
        val b = 1f - a
        val leftSpace = if (surroundLeft.isEmpty()) 0f else sqrt((1f - frontSpace) / surroundLeft.size)
        val rightSpace = if (surroundRight.isEmpty()) 0f else sqrt((1f - frontSpace) / surroundRight.size)
        val frontKeep = sqrt(if (surroundLeft.isEmpty() && surroundRight.isEmpty()) 1f else frontSpace)

        for (k in 0 until bins) {
            val xr = lr[k]
            val xi = li[k]
            val yr = rr[k]
            val yi = ri[k]
            val el = xr * xr + xi * xi
            val er = yr * yr + yi * yi
            pll[k] = a * pll[k] + b * el
            prr[k] = a * prr[k] + b * er
            plrRe[k] = a * plrRe[k] + b * (xr * yr + xi * yi)
            val pl = pll[k]
            val pr = prr[k]
            val total = pl + pr
            if (total < 1e-12f) continue
            // How much of this band is one source (coherent, in phase) rather than space.
            val lo = minOf(pl, pr)
            val coherent = if (lo < 1e-6f * total) 1f else (plrRe[k] / sqrt(pl * pr)).coerceIn(0f, 1f)
            val gp = sqrt(coherent)
            val ga = sqrt(1f - coherent)

            // Where the source sat in the stereo picture: -1 hard left to 1 hard right.
            val balance = ((pl - pr) / total).coerceIn(-1f, 1f)
            val place = -(asin(balance) / (PI / 2)).toFloat()
            val angle = place * halfWidth
            val energy = coherent * (el + er)
            val amp = sqrt(energy)
            if (amp > 0f) {
                // Between which two front speakers, and how much to each (power kept).
                var i0 = 0
                var i1 = 0
                var t = 0f
                val last = frontAngle.size - 1
                if (angle <= frontAngle[0]) {
                    i0 = 0; i1 = 0
                } else if (angle >= frontAngle[last]) {
                    i0 = last; i1 = last
                } else {
                    for (j in 0 until last) {
                        if (angle >= frontAngle[j] && angle <= frontAngle[j + 1]) {
                            i0 = j; i1 = j + 1
                            t = (angle - frontAngle[j]) / max(1e-3f, frontAngle[j + 1] - frontAngle[j])
                            break
                        }
                    }
                }
                val g0 = if (i0 == i1) 1f else cos(t * PI / 2).toFloat()
                val g1 = if (i0 == i1) 0f else sin(t * PI / 2).toFloat()
                addSource(k, frontIdx[i0], g0 * amp, xr, xi, yr, yi)
                if (g1 > 0f) addSource(k, frontIdx[i1], g1 * amp, xr, xi, yr, yi)
            }

            // The space, on its own side.
            if (ga > 0f) {
                val sl = ga * xr
                val sli = ga * xi
                val sr = ga * yr
                val sri = ga * yi
                if (frontLeft >= 0) {
                    outRe[frontLeft][k] += frontKeep * sl
                    outIm[frontLeft][k] += frontKeep * sli
                }
                if (frontRight >= 0) {
                    outRe[frontRight][k] += frontKeep * sr
                    outIm[frontRight][k] += frontKeep * sri
                }
                for (s in surroundLeft) {
                    outRe[s][k] += leftSpace * sl
                    outIm[s][k] += leftSpace * sli
                }
                for (s in surroundRight) {
                    outRe[s][k] += rightSpace * sr
                    outIm[s][k] += rightSpace * sri
                }
            }
        }

        for (s in 0 until speakers) {
            fft.inverse(outRe[s], outIm[s], time)
            val o = overlap[s]
            for (i in 0 until n) o[i] += time[i] * window[i]
            System.arraycopy(o, 0, out[s], 0, hop)
            System.arraycopy(o, hop, o, 0, n - hop)
            java.util.Arrays.fill(o, n - hop, n, 0f)
        }
    }

    /** Adds [amp] of band [k]'s source to speaker [s], in the phase of the channel(s) on its side. */
    private fun addSource(k: Int, s: Int, amp: Float, xr: Float, xi: Float, yr: Float, yi: Float) {
        var ur: Float
        var ui: Float
        when (side[s]) {
            -1 -> { ur = xr; ui = xi }
            1 -> { ur = yr; ui = yi }
            else -> { ur = xr + yr; ui = xi + yi }
        }
        var m = sqrt(ur * ur + ui * ui)
        if (m < 1e-9f) {
            ur = if (xr * xr + xi * xi >= yr * yr + yi * yi) xr else yr
            ui = if (xr * xr + xi * xi >= yr * yr + yi * yi) xi else yi
            m = sqrt(ur * ur + ui * ui)
            if (m < 1e-9f) return
        }
        outRe[s][k] += amp * ur / m
        outIm[s][k] += amp * ui / m
    }
}
