package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What each virtual speaker sounds like at each ear in the chosen room: its direct sound and
 * its reflections off the walls, floor and ceiling (worked out by mirror images of the speaker
 * in each surface, up to two bounces), each arriving from its own direction through the
 * measured head. Built off the audio thread whenever a setup changes.
 *
 * You sit facing the front wall, 40% of the way back, ears 1.2 m up. Coordinates: x ahead, y to
 * the left, z up, in metres from the middle of your head.
 */
internal object Room {
    const val SPEED = 343f
    private const val EAR_HEIGHT = 1.2f
    /** Room before the earliest sound, so ears that hear it a little earlier still fit. */
    const val MARGIN = 24

    class Built(
        /** [speaker][ear] -> impulse response, ear 0 is the left. */
        val filters: Array<Array<FloatArray>>,
        /** When the direct sound of the front speakers arrives, in samples: the bass waits as long. */
        val directDelay: Int,
        /** Where the tail should start, in samples. */
        val tailDelay: Int,
    )

    fun build(head: Head, setup: SpatialSetup, maxLength: Int): Built {
        val fs = head.sampleRate.toFloat()
        val room = setup.room
        val speakers = setup.speakers
        val walls = room != RoomKind.None
        val front = room.depth * 0.4f
        val back = room.depth * 0.6f
        val half = room.width / 2f

        // Where each speaker stands, kept clear of the walls.
        val positions = speakers.map { s ->
            val az = Math.toRadians(-s.angle.toDouble())
            val el = Math.toRadians(s.height.toDouble())
            var d = s.distance.coerceIn(SpatialSetup.MIN_DISTANCE, SpatialSetup.MAX_DISTANCE)
            var p = vec(d, az, el)
            if (walls) {
                val gap = 0.3f
                fun fits(q: FloatArray) = q[0] < front - gap && q[0] > -back + gap && abs(q[1]) < half - gap &&
                    q[2] > -EAR_HEIGHT + gap && q[2] < room.height - EAR_HEIGHT - gap
                while (!fits(p) && d > SpatialSetup.MIN_DISTANCE) {
                    d *= 0.95f
                    p = vec(d, az, el)
                }
            }
            p
        }
        val nearest = positions.minOf { len(it) }
        val reference = head.referenceDelay
        val dullA = if (walls) onePole(room.dullHz, fs) else 0f
        val beta = if (walls) sqrt(1f - room.absorption) * (setup.reflections * 1.6f).coerceIn(0f, 1.6f) else 0f

        class Arrival(val pos: Float, val gain: Float, val bounces: Int, val az: Float, val el: Float)

        val arrivals = positions.map { p ->
            val direct = len(p)
            val list = ArrayList<Arrival>()
            list += Arrival(MARGIN + (direct - nearest) / SPEED * fs, 1f, 0, azimuth(p), elevation(p))
            if (walls && beta > 0f) {
                val xs = images(p[0], -back, front, room.order)
                val ys = images(p[1], -half, half, room.order)
                val zs = images(p[2], -EAR_HEIGHT, room.height - EAR_HEIGHT, room.order)
                for (x in xs) for (y in ys) for (z in zs) {
                    val n = x.second + y.second + z.second
                    if (n == 0 || n > room.order) continue
                    val q = floatArrayOf(x.first, y.first, z.first)
                    val r = len(q)
                    if ((r - direct) / SPEED * 1000f > room.earlyMs) continue
                    val g = direct / r * beta.pow(n)
                    if (g < 0.003f) continue
                    list += Arrival(MARGIN + (r - nearest) / SPEED * fs, g, n, azimuth(q), elevation(q))
                }
            }
            list
        }

        val longest = arrivals.maxOf { a -> a.maxOf { it.pos } } + 2 * MARGIN + head.taps + 8
        val length = min(maxLength, ceil(longest).toInt())
        val scale = setup.headSize.scale
        val filters = Array(speakers.size) { Array(2) { FloatArray(length) } }
        var directSum = 0f
        val dulled = FloatArray(head.taps)
        for ((s, list) in arrivals.withIndex()) {
            for (a in list) {
                val h = head.at(a.az, a.el)
                val mid = (h.delayLeft + h.delayRight) / 2f
                val dl = mid + (h.delayLeft - mid) * scale - reference
                val dr = mid + (h.delayRight - mid) * scale - reference
                for ((ear, src, d) in listOf(Triple(0, h.left, dl), Triple(1, h.right, dr))) {
                    val ir = if (a.bounces > 0) dull(src, dullA, a.bounces, dulled) else src
                    place(filters[s][ear], ir, a.pos + d, a.gain)
                }
                if (a.bounces == 0) directSum += a.pos + (dl + dr) / 2f
            }
        }
        val directDelay = Math.round(directSum / speakers.size + head.onset)
        normalise(filters, setup, fs)

        val tailDelay = Math.round(MARGIN + room.earlyMs * 0.6f / 1000f * fs + head.onset)
        return Built(filters, directDelay, tailDelay)
    }

    /**
     * Scales everything so that switching spatial audio on doesn't change the loudness: the
     * stereo mix's typical spread over the speakers should reach each ear at the power it had
     * going straight into it.
     */
    private fun normalise(filters: Array<Array<FloatArray>>, setup: SpatialSetup, fs: Float) {
        val roles = setup.speakers.map { it.role }
        val surrounds = roles.count { it.surround }
        val share = roles.map { r ->
            when {
                setup.layout == SpatialLayout.Stereo -> 0.5f
                r.surround -> 0.4f / max(1, surrounds)
                else -> 0.2f
            }
        }
        var power = 0.0
        for ((s, pair) in filters.withIndex()) {
            val p = (bandPower(pair[0], fs) + bandPower(pair[1], fs)) / 2
            power += share[s] * p
        }
        val gain = if (power > 1e-9) (1.0 / sqrt(2 * power)).toFloat() else 1f
        for ((s, pair) in filters.withIndex()) {
            val sp = setup.speakers[s]
            var db = sp.levelDb
            if (sp.role == SpeakerRole.Centre) db += setup.centreDb
            if (sp.role.surround) db += setup.surroundDb
            val g = gain * 10f.pow(db / 20f)
            for (h in pair) for (i in h.indices) h[i] *= g
        }
    }

    /** Mean power from 300 Hz to 6 kHz, where hearing judges loudness most. */
    private fun bandPower(h: FloatArray, fs: Float): Double {
        var n = 1
        while (n < h.size) n *= 2
        n = max(n, 512)
        val fft = RealFft(n)
        val x = FloatArray(n)
        System.arraycopy(h, 0, x, 0, h.size)
        val re = FloatArray(fft.bins)
        val im = FloatArray(fft.bins)
        fft.forward(x, re, im)
        val lo = (300f / fs * n).toInt()
        val hi = (6000f / fs * n).toInt()
        var sum = 0.0
        for (k in lo..hi) sum += re[k] * re[k] + im[k] * im[k]
        return sum / (hi - lo + 1)
    }

    /** The mirror images of [s] between walls at [a] and [b], with how many bounces each took. */
    private fun images(s: Float, a: Float, b: Float, order: Int): List<Pair<Float, Int>> {
        val l = b - a
        val out = ArrayList<Pair<Float, Int>>()
        out += s to 0
        if (order >= 1) {
            out += (2 * a - s) to 1
            out += (2 * b - s) to 1
        }
        if (order >= 2) {
            out += (s + 2 * l) to 2
            out += (s - 2 * l) to 2
        }
        return out
    }

    /**
     * Adds [src] into [dst] starting at [pos] samples (fractional: through a short windowed sinc,
     * so arrivals land between samples without dulling).
     */
    private fun place(dst: FloatArray, src: FloatArray, pos: Float, gain: Float) {
        val i0 = floor(pos).toInt()
        val frac = pos - i0
        val w = FloatArray(8)
        var sum = 0f
        for (m in 0 until 8) {
            val x = (m - 3) - frac
            val v = sinc(x) * lanczos(x, 4f)
            w[m] = v
            sum += v
        }
        for (m in 0 until 8) w[m] /= sum
        for (j in src.indices) {
            val v = src[j] * gain
            if (v == 0f) continue
            for (m in 0 until 8) {
                val k = i0 + j + m - 3
                if (k in dst.indices) dst[k] += v * w[m]
            }
        }
    }

    private fun dull(src: FloatArray, a: Float, times: Int, out: FloatArray): FloatArray {
        System.arraycopy(src, 0, out, 0, src.size)
        repeat(times) {
            var y = 0f
            for (i in out.indices) {
                y += a * (out[i] - y)
                out[i] = y
            }
        }
        return out
    }

    private fun onePole(hz: Float, fs: Float): Float = (1.0 - exp(-2.0 * PI * hz / fs)).toFloat()

    private fun sinc(x: Float): Float = if (abs(x) < 1e-6f) 1f else (sin(PI * x) / (PI * x)).toFloat()

    private fun lanczos(x: Float, a: Float): Float = if (abs(x) >= a) 0f else sinc(x / a)

    private fun vec(d: Float, az: Double, el: Double) = floatArrayOf(
        (d * cos(el) * cos(az)).toFloat(),
        (d * cos(el) * sin(az)).toFloat(),
        (d * sin(el)).toFloat(),
    )

    private fun len(p: FloatArray) = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])

    /** Anticlockwise from ahead, as the head was measured. */
    private fun azimuth(p: FloatArray) = Math.toDegrees(atan2(p[1].toDouble(), p[0].toDouble())).toFloat()

    private fun elevation(p: FloatArray) = Math.toDegrees(atan2(p[2].toDouble(), hypot(p[0].toDouble(), p[1].toDouble()))).toFloat()
}
