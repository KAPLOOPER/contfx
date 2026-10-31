package dev.continuum.fx.dsp

import kotlin.math.max

/**
 * Long filters at a small, fixed cost: uniformly partitioned convolution (overlap-save). Each
 * input (a virtual speaker) reaches each output (an ear) through its own filter, cut into
 * [block]-long pieces whose spectra are made ahead of time; every block, each input is
 * transformed once and multiplied against all the pieces. Latency is one block.
 *
 * New filters fade in over one block from the old ones, on the same input history, so moving a
 * speaker or changing the room never clicks.
 */
internal class Convolver(val block: Int, val maxInputs: Int, val maxPartitions: Int, val outputs: Int = 2) {

    /** Filter spectra, made off the audio thread by [prepare]. */
    class Filters internal constructor(
        val inputs: Int,
        val outputs: Int,
        val partitions: Int,
        /** [input][output][partition] -> bins. */
        internal val re: Array<Array<Array<FloatArray>>>,
        internal val im: Array<Array<Array<FloatArray>>>,
    )

    private val fft = RealFft(2 * block)
    private val bins = block + 1

    // The input history: each input's last spectra, newest at [slot].
    private val fdlRe = Array(maxInputs) { Array(maxPartitions) { FloatArray(bins) } }
    private val fdlIm = Array(maxInputs) { Array(maxPartitions) { FloatArray(bins) } }
    private val previous = Array(maxInputs) { FloatArray(block) }
    private var slot = 0
    private var fed = 0

    private val frame = FloatArray(2 * block)
    private val accRe = FloatArray(bins)
    private val accIm = FloatArray(bins)
    private val time = FloatArray(2 * block)
    private val other = Array(outputs) { FloatArray(block) }

    private var current: Filters? = null

    @Volatile
    private var pending: Filters? = null

    /** Swaps to [filters] (or silence, for null) with a one-block crossfade. Any thread. */
    fun set(filters: Filters?) {
        require(filters == null || (filters.inputs <= maxInputs && filters.partitions <= maxPartitions && filters.outputs == outputs))
        pending = filters ?: EMPTY
    }

    fun reset() {
        for (i in 0 until maxInputs) {
            previous[i].fill(0f)
            for (p in 0 until maxPartitions) {
                fdlRe[i][p].fill(0f)
                fdlIm[i][p].fill(0f)
            }
        }
    }

    /**
     * One block: [inputs] (the first [count], each [block] samples) to [out] (one array per
     * output, [block] samples each, overwritten).
     */
    fun process(inputs: Array<FloatArray>, count: Int, out: Array<FloatArray>) {
        val n = minOf(count, maxInputs)
        // Inputs that weren't being fed carry an old history: start them clean.
        if (n > fed) {
            for (i in fed until n) {
                previous[i].fill(0f)
                for (p in 0 until maxPartitions) {
                    fdlRe[i][p].fill(0f)
                    fdlIm[i][p].fill(0f)
                }
            }
        }
        fed = n
        slot = (slot + 1) % maxPartitions
        for (i in 0 until n) {
            System.arraycopy(previous[i], 0, frame, 0, block)
            System.arraycopy(inputs[i], 0, frame, block, block)
            System.arraycopy(inputs[i], 0, previous[i], 0, block)
            fft.forward(frame, fdlRe[i][slot], fdlIm[i][slot])
        }

        val next = pending
        val now = current
        if (next == null) {
            render(now, n, out)
            return
        }
        pending = null
        val target = if (next === EMPTY) null else next
        render(now, n, out)
        render(target, n, other)
        for (o in 0 until outputs) {
            val a = out[o]
            val b = other[o]
            for (s in 0 until block) {
                val t = (s + 1f) / block
                a[s] = a[s] * (1f - t) + b[s] * t
            }
        }
        current = target
    }

    private fun render(f: Filters?, n: Int, out: Array<FloatArray>) {
        if (f == null) {
            for (o in 0 until outputs) out[o].fill(0f)
            return
        }
        val inputs = minOf(n, f.inputs)
        for (o in 0 until outputs) {
            accRe.fill(0f)
            accIm.fill(0f)
            for (i in 0 until inputs) {
                val hRe = f.re[i][o]
                val hIm = f.im[i][o]
                for (p in 0 until f.partitions) {
                    var s = slot - p
                    if (s < 0) s += maxPartitions
                    val xr = fdlRe[i][s]
                    val xi = fdlIm[i][s]
                    val hr = hRe[p]
                    val hi = hIm[p]
                    for (k in 0 until bins) {
                        val a = xr[k]
                        val b = xi[k]
                        val c = hr[k]
                        val d = hi[k]
                        accRe[k] += a * c - b * d
                        accIm[k] += a * d + b * c
                    }
                }
            }
            fft.inverse(accRe, accIm, time)
            System.arraycopy(time, block, out[o], 0, block)
        }
    }

    companion object {
        private val EMPTY = Filters(0, 0, 0, emptyArray(), emptyArray())

        /** Filter spectra for [irs] ([input][output] -> impulse response), for blocks of [block]. */
        fun prepare(block: Int, irs: Array<Array<FloatArray>>): Filters {
            val fft = RealFft(2 * block)
            val inputs = irs.size
            val outputs = irs.firstOrNull()?.size ?: 0
            var longest = 1
            for (row in irs) for (h in row) longest = max(longest, h.size)
            val partitions = (longest + block - 1) / block
            val frame = FloatArray(2 * block)
            val re = Array(inputs) { Array(outputs) { Array(partitions) { FloatArray(block + 1) } } }
            val im = Array(inputs) { Array(outputs) { Array(partitions) { FloatArray(block + 1) } } }
            for (i in 0 until inputs) for (o in 0 until outputs) {
                val h = irs[i][o]
                for (p in 0 until partitions) {
                    frame.fill(0f)
                    val from = p * block
                    val count = minOf(block, h.size - from)
                    if (count > 0) System.arraycopy(h, from, frame, 0, count)
                    fft.forward(frame, re[i][o][p], im[i][o][p])
                }
            }
            return Filters(inputs, outputs, partitions, re, im)
        }
    }
}
