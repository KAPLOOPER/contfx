package dev.continuum.fx.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * A measured head: how a sound from each direction arrives at each ear (head-related impulse
 * responses), from the SADIE II database (University of York, Apache 2.0), converted by
 * tools/hrir/convert.py. The responses are diffuse-field equalised, so placing a sound changes
 * where it is and not its tone, and each ear's arrival time is kept apart from its response,
 * so directions between the measured ones blend cleanly.
 *
 * Directions here are as measured: azimuth in degrees anticlockwise from straight ahead (90 is
 * the left), elevation in degrees up from the ears' level.
 */
internal class Head private constructor(
    val sampleRate: Int,
    val taps: Int,
    /** Where, in each response, the first ear's sound starts. */
    val onset: Int,
    private val rows: List<Row>,
) {
    private class Row(
        val elevation: Float,
        val azimuth: FloatArray,
        val delayLeft: FloatArray,
        val delayRight: FloatArray,
        val left: Array<FloatArray>,
        val right: Array<FloatArray>,
    )

    /**
     * The typical arrival time across all directions: delays are taken relative to it, so only
     * what differs between directions and ears is kept.
     */
    val referenceDelay: Float = run {
        var sum = 0.0
        var n = 0
        for (row in rows) for (i in row.azimuth.indices) {
            sum += (row.delayLeft[i] + row.delayRight[i]) / 2.0
            n++
        }
        if (n == 0) 0f else (sum / n).toFloat()
    }

    /** One direction's responses, and how much later than [onset] each ear hears it. */
    class Response(val left: FloatArray, val right: FloatArray, var delayLeft: Float, var delayRight: Float)

    /** The response from ([azimuth], [elevation]), blended from the four nearest measurements. */
    fun at(azimuth: Float, elevation: Float): Response {
        val out = Response(FloatArray(taps), FloatArray(taps), 0f, 0f)
        val e = elevation.coerceIn(rows.first().elevation, rows.last().elevation)
        var hi = rows.indexOfFirst { it.elevation >= e }
        if (hi < 0) hi = rows.size - 1
        val lo = if (rows[hi].elevation > e && hi > 0) hi - 1 else hi
        val t = if (hi == lo) 0f else (e - rows[lo].elevation) / (rows[hi].elevation - rows[lo].elevation)
        add(rows[lo], azimuth, 1f - t, out)
        if (hi != lo) add(rows[hi], azimuth, t, out)
        return out
    }

    private fun add(row: Row, azimuth: Float, weight: Float, out: Response) {
        if (weight <= 0f) return
        val n = row.azimuth.size
        if (n == 1) {
            mix(row, 0, weight, out)
            return
        }
        val step = 360f / n
        var a = azimuth % 360f
        if (a < 0f) a += 360f
        val pos = a / step
        val i0 = floor(pos).toInt() % n
        val i1 = (i0 + 1) % n
        val f = pos - floor(pos)
        mix(row, i0, weight * (1f - f), out)
        mix(row, i1, weight * f, out)
    }

    private fun mix(row: Row, i: Int, w: Float, out: Response) {
        if (w <= 0f) return
        val l = row.left[i]
        val r = row.right[i]
        for (k in 0 until taps) {
            out.left[k] += w * l[k]
            out.right[k] += w * r[k]
        }
        out.delayLeft += w * row.delayLeft[i]
        out.delayRight += w * row.delayRight[i]
    }

    companion object {
        /** Reads a head file (see tools/hrir/convert.py for the layout). */
        fun parse(bytes: ByteArray): Head {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(5)
            b.get(magic)
            require(String(magic, Charsets.US_ASCII) == "CHRIR") { "Not a head file" }
            val version = b.get().toInt()
            require(version == 1) { "Head file version $version" }
            val sampleRate = b.int
            val taps = b.int
            val onset = b.int
            val scale = b.float
            val rowCount = b.int
            val rows = ArrayList<Row>(rowCount)
            repeat(rowCount) {
                val el = b.float
                val n = b.int
                val az = FloatArray(n)
                val dl = FloatArray(n)
                val dr = FloatArray(n)
                val left = Array(n) { FloatArray(taps) }
                val right = Array(n) { FloatArray(taps) }
                for (i in 0 until n) {
                    az[i] = b.float
                    dl[i] = b.float
                    dr[i] = b.float
                    for (k in 0 until taps) left[i][k] = b.short * scale
                    for (k in 0 until taps) right[i][k] = b.short * scale
                }
                rows += Row(el, az, dl, dr, left, right)
            }
            return Head(sampleRate, taps, onset, rows.sortedBy { it.elevation })
        }
    }
}
