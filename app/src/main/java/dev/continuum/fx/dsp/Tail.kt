package dev.continuum.fx.dsp

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The room's tail, after the worked-out reflections: eight delay lines feeding back into each
 * other through a mixing matrix that loses nothing (a feedback delay network), each line
 * losing just enough per trip to die away in [decay] seconds, and losing its top faster, as
 * air and soft furnishings do. Cheap, smooth and dense; kept quiet by default.
 */
internal class Tail(private val sampleRate: Double) {
    private val lines = 8
    private val maxLen = (sampleRate * 0.2).toInt()
    private val buffers = Array(lines) { FloatArray(maxLen) }
    private val lengths = IntArray(lines)
    private val gains = FloatArray(lines)
    private val lows = FloatArray(lines)
    private var lowA = 0.3f
    private var pos = 0
    private var norm = 1f

    private val preMax = (sampleRate * 0.12).toInt()
    private val preL = FloatArray(preMax)
    private val preR = FloatArray(preMax)
    private var prePos = 0
    private var preDelay = 0

    private val v = FloatArray(lines)

    /** [size] scales the lines (bigger rooms, sparser echoes); [dullHz] is where the top dies faster. */
    fun configure(decay: Float, size: Float, preDelaySamples: Int, dullHz: Float) {
        for (i in 0 until lines) {
            val len = (BASE_MS[i] * size / 1000.0 * sampleRate).toInt().coerceIn(32, maxLen - 1)
            lengths[i] = len
            gains[i] = 10.0.pow(-3.0 * len / (sampleRate * decay.coerceIn(0.05f, 3f))).toFloat()
        }
        lowA = (1.0 - exp(-2.0 * PI * dullHz / sampleRate)).toFloat()
        // Roughly unity power for a steady input, whatever the decay.
        var sum = 0.0
        for (g in gains) sum += 1.0 / (1.0 - g * g)
        norm = (1.0 / sqrt(sum / lines)).toFloat()
        preDelay = preDelaySamples.coerceIn(0, preMax - 1)
    }

    fun reset() {
        for (b in buffers) b.fill(0f)
        lows.fill(0f)
        preL.fill(0f)
        preR.fill(0f)
    }

    /** Adds the tail of [inL]/[inR] times [level] into [outL]/[outR]. */
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, level: Float) {
        val a = lowA
        for (s in 0 until n) {
            preL[prePos] = inL[s]
            preR[prePos] = inR[s]
            var rp = prePos - preDelay
            if (rp < 0) rp += preMax
            val xl = preL[rp] * norm
            val xr = preR[rp] * norm
            prePos = (prePos + 1) % preMax

            for (i in 0 until lines) {
                var rd = pos - lengths[i]
                if (rd < 0) rd += maxLen
                val y = buffers[i][rd]
                lows[i] += a * (y - lows[i])
                v[i] = lows[i] * gains[i]
            }
            var l = 0f
            var r = 0f
            for (i in 0 until lines step 2) {
                l += if (i % 4 == 0) v[i] else -v[i]
                r += if (i % 4 == 0) v[i + 1] else -v[i + 1]
            }
            hadamard(v)
            for (i in 0 until lines) {
                val input = if (i % 2 == 0) xl else xr
                buffers[i][pos] = v[i] + (if (i < 4) input else -input) * 0.5f
            }
            pos = (pos + 1) % maxLen
            outL[s] += l * level * 0.5f
            outR[s] += r * level * 0.5f
        }
    }

    /** A lossless mix of the eight lines into each other. */
    private fun hadamard(x: FloatArray) {
        var h = 1
        while (h < lines) {
            var i = 0
            while (i < lines) {
                for (j in i until i + h) {
                    val a = x[j]
                    val b = x[j + h]
                    x[j] = a + b
                    x[j + h] = a - b
                }
                i += 2 * h
            }
            h *= 2
        }
        val s = 1f / sqrt(lines.toFloat())
        for (i in 0 until lines) x[i] *= s
    }

    private companion object {
        /** Line lengths for a medium room, chosen not to share factors. */
        val BASE_MS = doubleArrayOf(29.7, 37.1, 41.1, 43.7, 47.3, 53.0, 59.3, 67.1)
    }
}
