package dev.continuum.keyboard.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a swipe across the letters into words. Each word has an ideal path through the
 * centres of its letters; the swipe is compared with the paths of words that start and
 * end near where it did and pass close to every letter along the way, and the closest
 * and most likely win.
 */
class SwipeDecoder(private val words: Words, private val limit: Int = 60_000) {

    private class Entry(val word: Int, val path: String)

    /** Words grouped by first and last letter of their path. */
    private val index: Map<Int, List<Entry>>

    init {
        val map = HashMap<Int, MutableList<Entry>>()
        val count = min(words.size, limit)
        for (w in 0 until count) {
            val path = pathOf(words.word(w)) ?: continue
            if (path.length < 2) continue
            map.getOrPut(pair(path.first(), path.last())) { ArrayList() } += Entry(w, path)
        }
        index = map
    }

    data class Result(val word: String, val score: Float, val freq: Int)

    /**
     * Decodes a swipe given as x and y in pixels. [extra] adds words that aren't in the
     * list (learned ones). [score] lets the caller add context, in Zipf units.
     */
    fun decode(
        xs: FloatArray,
        ys: FloatArray,
        keys: KeyMap,
        extra: Words? = null,
        context: (String) -> Float = { 0f },
        boost: (String) -> Float = { 0f },
        count: Int = 6,
    ): List<Result> {
        if (xs.size < 2) return emptyList()
        val (px, py) = normalise(xs, ys, keys)
        val fine = resample(px, py, 96)
        val coarse = resample(px, py, SAMPLES)
        val userLength = length(px, py)
        if (userLength < 0.6f) return emptyList()

        val starts = nearKeys(keys, fine.first[0], fine.second[0], 1.25f)
        val ends = nearKeys(keys, fine.first.last(), fine.second.last(), 1.25f)
        if (starts.isEmpty() || ends.isEmpty()) return emptyList()

        val results = ArrayList<Result>()
        fun consider(word: String, path: String, freq: Int) {
            val ideal = idealPath(path, keys) ?: return
            val idealLength = length(ideal.first, ideal.second)
            if (idealLength > userLength * 1.7f + 1.5f || idealLength < userLength * 0.45f - 1f) return
            val letterFit = passesThrough(path, keys, fine.first, fine.second) ?: return
            val idealCoarse = resample(ideal.first, ideal.second, SAMPLES)
            var sum = 0f
            for (i in 0 until SAMPLES) {
                val dx = coarse.first[i] - idealCoarse.first[i]
                val dy = coarse.second[i] - idealCoarse.second[i]
                sum += sqrt(dx * dx + dy * dy)
            }
            val location = sum / SAMPLES
            val lengthMiss = abs(idealLength - userLength) / max(1f, userLength)
            val cost = 1.5f * location + 1.1f * letterFit + 0.6f * lengthMiss
            val score = freq / 10f + boost(word) + context(word) - 4.5f * cost
            results += Result(word, score, freq)
        }

        for (s in starts) for (e in ends) {
            index[pair(s, e)]?.forEach { consider(words.word(it.word), it.path, words.frequency(it.word)) }
        }
        if (extra != null) {
            for (w in 0 until extra.size) {
                val word = extra.word(w)
                val path = pathOf(word) ?: continue
                if (path.length < 2 || path.first() !in starts || path.last() !in ends) continue
                consider(word, path, extra.frequency(w))
            }
        }
        return results
            .groupBy { Words.keyOf(it.word) }
            .map { (_, same) -> same.maxBy { it.score } }
            .sortedByDescending { it.score }
            .take(count)
    }

    /**
     * Checks the swipe passes close to each letter in order. Returns the average miss
     * (in keys), or null when a letter is never reached.
     */
    private fun passesThrough(path: String, keys: KeyMap, xs: FloatArray, ys: FloatArray): Float? {
        var at = 0
        var total = 0f
        val last = path.length - 1
        for ((i, c) in path.withIndex()) {
            val k = keys.centre(c) ?: return null
            val cx = k[0] / keys.keyWidth
            val cy = k[1] / keys.keyHeight
            val reach = if (i == 0 || i == last) 1.25f else 0.95f
            if (i == last) {
                // The last letter is where the swipe ends.
                val d = dist(xs.last(), ys.last(), cx, cy)
                if (d > reach) return null
                total += d
                break
            }
            var found = -1
            var bestD = Float.MAX_VALUE
            var j = at
            while (j < xs.size) {
                val d = dist(xs[j], ys[j], cx, cy)
                if (found == -1) {
                    if (d <= reach) { found = j; bestD = d }
                } else {
                    if (d < bestD) { bestD = d; found = j } else if (d > bestD + 0.25f) break
                }
                j++
            }
            if (found == -1) return null
            total += bestD
            at = found
        }
        return total / path.length
    }

    private fun nearKeys(keys: KeyMap, x: Float, y: Float, radius: Float): List<Char> =
        keys.letters.filter {
            val k = keys.centre(it)!!
            dist(x, y, k[0] / keys.keyWidth, k[1] / keys.keyHeight) <= radius
        }

    private fun idealPath(path: String, keys: KeyMap): Pair<FloatArray, FloatArray>? {
        val xs = FloatArray(path.length)
        val ys = FloatArray(path.length)
        for ((i, c) in path.withIndex()) {
            val k = keys.centre(c) ?: return null
            xs[i] = k[0] / keys.keyWidth
            ys[i] = k[1] / keys.keyHeight
        }
        return xs to ys
    }

    companion object {
        private const val SAMPLES = 40

        private fun pair(a: Char, b: Char) = (a.code shl 16) or b.code

        /** The letters a swipe for [word] passes over, with repeats merged ("hello" is h-e-l-o). */
        fun pathOf(word: String): String? {
            val key = Words.keyOf(word)
            val sb = StringBuilder(key.length)
            for (c in key) {
                if (c == '\'' || c == '-') continue
                if (c !in 'a'..'z') return null
                if (sb.isEmpty() || sb.last() != c) sb.append(c)
            }
            return sb.toString()
        }

        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return sqrt(dx * dx + dy * dy)
        }

        /** Converts pixels to key units. */
        private fun normalise(xs: FloatArray, ys: FloatArray, keys: KeyMap): Pair<FloatArray, FloatArray> =
            FloatArray(xs.size) { xs[it] / keys.keyWidth } to FloatArray(ys.size) { ys[it] / keys.keyHeight }

        fun length(xs: FloatArray, ys: FloatArray): Float {
            var total = 0f
            for (i in 1 until xs.size) total += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            return total
        }

        /** [n] points evenly spaced along the path. */
        fun resample(xs: FloatArray, ys: FloatArray, n: Int): Pair<FloatArray, FloatArray> {
            val ox = FloatArray(n)
            val oy = FloatArray(n)
            val total = length(xs, ys)
            if (xs.size == 1 || total <= 0f) {
                ox.fill(xs[0]); oy.fill(ys[0])
                return ox to oy
            }
            val step = total / (n - 1)
            ox[0] = xs[0]; oy[0] = ys[0]
            var k = 1
            var travelled = 0f
            var i = 1
            var lx = xs[0]
            var ly = ys[0]
            while (i < xs.size && k < n) {
                val seg = dist(lx, ly, xs[i], ys[i])
                if (travelled + seg >= step * k - 1e-6f && seg > 0f) {
                    val t = (step * k - travelled) / seg
                    val nx = lx + (xs[i] - lx) * t
                    val ny = ly + (ys[i] - ly) * t
                    ox[k] = nx; oy[k] = ny
                    k++
                    travelled += dist(lx, ly, nx, ny)
                    lx = nx; ly = ny
                } else {
                    travelled += seg
                    lx = xs[i]; ly = ys[i]
                    i++
                }
            }
            while (k < n) { ox[k] = xs.last(); oy[k] = ys.last(); k++ }
            return ox to oy
        }
    }
}
