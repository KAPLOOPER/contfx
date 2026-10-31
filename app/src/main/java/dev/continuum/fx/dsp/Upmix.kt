package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Splits a stereo mix into its sources and its space, band by band and moment to moment: what
 * the two channels share, in phase (a voice, an instrument), is a source with a place in the
 * mix; what differs (reverb, crowd, stereo ambience, the surrounds that Android folded in) is
 * the space around it.
 *
 * The space is what makes headphones sound wide. Played from speakers in front of you, each ear
 * hears both, and it closes in; so a share of it ([space]) skips the speakers and goes straight
 * to the ears, as wide as it was. The rest goes to the speakers: for surround layouts mostly to
 * the surrounds on its own side.
 *
 * Sources stay as the stereo had them, except, for the surround layouts, the clearly placed
 * ones (fully shared, like a lead vocal) which are sent to the front speakers either side of
 * where they sat, so a centred voice comes from the centre speaker. Anything only partly shared
 * stays in the left and right speakers, so the front doesn't collapse into the middle.
 *
 * Done on 50%-overlapped frames of 2 x [hop] samples, so it delays by [hop].
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

    /** Speakers, then the two ears' straight-through space. */
    private var channels = 0
    private var speakers = 0
    private var outRe = Array(0) { FloatArray(0) }
    private var outIm = Array(0) { FloatArray(0) }
    private var overlap = Array(0) { FloatArray(0) }
    private val time = FloatArray(n)

    // The layout, in the form the per-band work needs.
    private var steer = false
    private var frontIdx = IntArray(0)
    private var frontAngle = FloatArray(0)
    private var side = IntArray(0)
    private var surroundLeft = IntArray(0)
    private var surroundRight = IntArray(0)
    private var frontLeft = -1
    private var frontRight = -1
    private var halfWidth = 30f
    private var space = 0f

    /** How much of the space the speakers get stays in the front ones. */
    private val frontSpace = 0.35f

    /**
     * [space] is the share (0 to 1) of the mix's space that goes straight to the ears. The
     * layout's centre and surrounds, if it has them, turn on the steering of sources.
     */
    fun configure(layout: List<Speaker>, width: Float, space: Float) {
        speakers = layout.size
        val wanted = speakers + 2
        if (channels != wanted) {
            channels = wanted
            outRe = Array(wanted) { FloatArray(bins) }
            outIm = Array(wanted) { FloatArray(bins) }
            overlap = Array(wanted) { FloatArray(n) }
        }
        this.space = space.coerceIn(0f, 1f)
        steer = layout.any { it.role == SpeakerRole.Centre || it.role.surround }
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

    /**
     * [hop] samples of each channel in; [hop] samples per speaker out (into [out]), and the
     * space that goes straight to the left and right ears into [ears].
     */
    fun process(l: FloatArray, r: FloatArray, out: Array<FloatArray>, ears: Array<FloatArray>) {
        System.arraycopy(historyL, hop, historyL, 0, n - hop)
        System.arraycopy(historyR, hop, historyR, 0, n - hop)
        System.arraycopy(l, 0, historyL, n - hop, hop)
        System.arraycopy(r, 0, historyR, n - hop, hop)
        for (i in 0 until n) frame[i] = historyL[i] * window[i]
        fft.forward(frame, lr, li)
        for (i in 0 until n) frame[i] = historyR[i] * window[i]
        fft.forward(frame, rr, ri)

        for (s in 0 until channels) {
            outRe[s].fill(0f)
            outIm[s].fill(0f)
        }
        val earL = speakers
        val earR = speakers + 1
        val a = smooth
        val b = 1f - a
        val hasSurrounds = surroundLeft.isNotEmpty() || surroundRight.isNotEmpty()
        val leftSpace = if (surroundLeft.isEmpty()) 0f else sqrt((1f - frontSpace) / surroundLeft.size)
        val rightSpace = if (surroundRight.isEmpty()) 0f else sqrt((1f - frontSpace) / surroundRight.size)
        val frontKeep = sqrt(if (hasSurrounds) frontSpace else 1f)
        val toSpeakers = sqrt(1f - space)
        val toEars = sqrt(space)

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
            // How much of this band is a source (shared, in phase) rather than space.
            val lo = minOf(pl, pr)
            val coherent = if (lo < 1e-6f * total) 1f else (plrRe[k] / sqrt(pl * pr)).coerceIn(0f, 1f)
            val ambient = 1f - coherent

            // Sources: the clearly placed ones steered (surround layouts), the rest as they were.
            val steered = if (steer) coherent * coherent else 0f
            val kept = sqrt(coherent - steered)
            if (frontLeft >= 0) {
                outRe[frontLeft][k] += kept * xr
                outIm[frontLeft][k] += kept * xi
            }
            if (frontRight >= 0) {
                outRe[frontRight][k] += kept * yr
                outIm[frontRight][k] += kept * yi
            }
            if (steered > 0f) {
                val balance = ((pl - pr) / total).coerceIn(-1f, 1f)
                val angle = -(asin(balance) / (PI / 2)).toFloat() * halfWidth
                val amp = sqrt(steered * (el + er))
                if (amp > 0f) {
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
            }

            // The space: a share straight to the ears, the rest to the speakers on its side.
            if (ambient > 0f) {
                val ga = sqrt(ambient)
                val sl = ga * xr
                val sli = ga * xi
                val sr = ga * yr
                val sri = ga * yi
                outRe[earL][k] += toEars * sl
                outIm[earL][k] += toEars * sli
                outRe[earR][k] += toEars * sr
                outIm[earR][k] += toEars * sri
                val f = toSpeakers * frontKeep
                if (frontLeft >= 0) {
                    outRe[frontLeft][k] += f * sl
                    outIm[frontLeft][k] += f * sli
                }
                if (frontRight >= 0) {
                    outRe[frontRight][k] += f * sr
                    outIm[frontRight][k] += f * sri
                }
                for (s in surroundLeft) {
                    outRe[s][k] += toSpeakers * leftSpace * sl
                    outIm[s][k] += toSpeakers * leftSpace * sli
                }
                for (s in surroundRight) {
                    outRe[s][k] += toSpeakers * rightSpace * sr
                    outIm[s][k] += toSpeakers * rightSpace * sri
                }
            }
        }

        for (s in 0 until channels) {
            fft.inverse(outRe[s], outIm[s], time)
            val o = overlap[s]
            for (i in 0 until n) o[i] += time[i] * window[i]
            val dst = if (s < speakers) out[s] else ears[s - speakers]
            System.arraycopy(o, 0, dst, 0, hop)
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
            val leftLouder = xr * xr + xi * xi >= yr * yr + yi * yi
            ur = if (leftLouder) xr else yr
            ui = if (leftLouder) xi else yi
            m = sqrt(ur * ur + ui * ui)
            if (m < 1e-9f) return
        }
        outRe[s][k] += amp * ur / m
        outIm[s][k] += amp * ui / m
    }
}
