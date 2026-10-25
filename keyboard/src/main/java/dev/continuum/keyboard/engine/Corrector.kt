package dev.continuum.keyboard.engine

import java.util.PriorityQueue
import kotlin.math.min

/** What was typed for the current word, with where each key was touched (NaN when unknown). */
class Typed(val text: String, xs: FloatArray? = null, ys: FloatArray? = null) {
    val key: String = Words.keyOf(text)
    val xs: FloatArray = if (xs != null && xs.size == key.length) xs else FloatArray(key.length) { Float.NaN }
    val ys: FloatArray = if (ys != null && ys.size == key.length) ys else FloatArray(key.length) { Float.NaN }
    val length: Int get() = key.length

    companion object {
        fun of(text: String) = Typed(text)
    }
}

/** A word that might be what was meant. */
data class Candidate(
    val word: String,
    /** How far it is from what was typed: about 1 per typo, less for near misses. */
    val cost: Float,
    /** Zipf x10. */
    val freq: Int,
    /** True when it continues past what was typed. */
    val completion: Boolean,
    /** True when it came from the words this keyboard has learned. */
    val learned: Boolean = false,
)

/**
 * Finds the words closest to what was typed by walking the trie with an edit-distance row,
 * where a slip onto a neighbouring key costs less than a real typo, a missing apostrophe
 * costs almost nothing, and doubled letters are cheap to add or drop.
 */
class Corrector(private val keys: KeyMap?) {

    private class Root(val node: Int, val cost: Float, val best: Int)

    fun search(
        words: Words,
        typed: Typed,
        maxCost: Float,
        completions: Boolean,
        learned: Boolean = false,
        limit: Int = 40,
    ): List<Candidate> {
        val n = typed.length
        if (n == 0 || words.size == 0) return emptyList()
        val key = typed.key
        val maxDepth = n + 2
        val rows = Array(maxDepth + 2) { FloatArray(n + 1) }
        val first = rows[0]
        for (j in 1..n) first[j] = first[j - 1] + del(key, j - 1)

        val exact = HashMap<Int, Float>()
        val roots = ArrayList<Root>()
        val completionBound = when {
            n <= 1 -> 0f
            n == 2 -> 0.6f
            else -> min(1.0f, maxCost)
        }

        fun visit(node: Int, depth: Int, prevChar: Char, prev: FloatArray, prevPrev: FloatArray?) {
            var child = words.firstChild(node)
            while (child != -1) {
                val c = words.char(child)
                val r = rows[depth + 1]
                val insCost = ins(c, prevChar)
                r[0] = prev[0] + insCost
                var lowest = r[0]
                for (j in 1..n) {
                    var v = prev[j - 1] + sub(typed, j - 1, c)
                    val a = prev[j] + insCost
                    if (a < v) v = a
                    val b = r[j - 1] + del(key, j - 1)
                    if (b < v) v = b
                    if (prevPrev != null && j >= 2 && c == key[j - 2] && prevChar == key[j - 1] && c != prevChar) {
                        val t = prevPrev[j - 2] + TRANSPOSE
                        if (t < v) v = t
                    }
                    r[j] = v
                    if (v < lowest) lowest = v
                }
                if (lowest <= maxCost) {
                    val end = r[n]
                    if (end <= maxCost) {
                        var w = words.terminal(child)
                        while (w != -1) {
                            val had = exact[w]
                            if (had == null || end < had) exact[w] = end
                            w = words.sameKey(w)
                        }
                    }
                    if (completions && depth + 1 >= n && end <= completionBound && words.firstChild(child) != -1) {
                        roots += Root(child, end, words.maxFreq(child))
                    }
                    if (depth + 1 < maxDepth) visit(child, depth + 1, c, r, prev)
                }
                child = words.nextSibling(child)
            }
        }
        visit(words.root, 0, '\u0000', first, null)

        val out = ArrayList<Candidate>(exact.size + 16)
        for ((w, cost) in exact) out += Candidate(words.word(w), cost, words.frequency(w), false, learned)

        if (roots.isNotEmpty()) {
            roots.sortWith(compareBy<Root> { it.cost }.thenByDescending { it.best })
            val seen = HashSet<Int>(exact.keys)
            for (root in roots.take(24)) {
                for (w in topBelow(words, root.node, 3)) {
                    if (!seen.add(w)) continue
                    out += Candidate(words.word(w), root.cost, words.frequency(w), true, learned)
                }
            }
        }
        out.sortByDescending { it.freq - 32f * it.cost - if (it.completion) 8f else 0f }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /** The [count] most frequent words strictly below [node]. */
    private fun topBelow(words: Words, node: Int, count: Int): List<Int> {
        val found = ArrayList<Int>(count)
        val queue = PriorityQueue<Int>(16) { a, b -> words.maxFreq(b) - words.maxFreq(a) }
        var c = words.firstChild(node)
        while (c != -1) { queue += c; c = words.nextSibling(c) }
        val pending = PriorityQueue<Int>(8) { a, b -> words.frequency(b) - words.frequency(a) }
        while (found.size < count && (queue.isNotEmpty() || pending.isNotEmpty())) {
            val nextNode = queue.peek()
            val nextWord = pending.peek()
            if (nextWord != null && (nextNode == null || words.frequency(nextWord) >= words.maxFreq(nextNode))) {
                found += pending.poll()!!
                continue
            }
            val at = queue.poll() ?: break
            var w = words.terminal(at)
            while (w != -1) { pending += w; w = words.sameKey(w) }
            var ch = words.firstChild(at)
            while (ch != -1) { queue += ch; ch = words.nextSibling(ch) }
        }
        return found
    }

    /** Cost of reading typed position [j] as [c]. */
    private fun sub(typed: Typed, j: Int, c: Char): Float {
        val t = typed.key[j]
        if (c == t) return 0f
        if (c == '\'' || c == '-' || t == '\'' || t == '-') return 1f
        val km = keys ?: return 1f
        val x = typed.xs[j]
        if (!x.isNaN()) {
            val d = km.distance(c, x, typed.ys[j])
            if (d == Float.MAX_VALUE) return 1f
            return 0.15f + 0.85f * min(1f, d * d / 1.2f)
        }
        val gap = km.between(t, c)
        return if (gap <= 1.25f) 0.6f else 1f
    }

    /** Cost of a letter in the word that wasn't typed. */
    private fun ins(c: Char, prevChar: Char): Float = when {
        c == '\'' || c == '-' -> 0.05f
        c == prevChar -> 0.3f
        else -> 1f
    }

    /** Cost of a typed letter that isn't in the word. */
    private fun del(key: String, j: Int): Float {
        val t = key[j]
        return when {
            t == '\'' || t == '-' -> 0.3f
            j > 0 && key[j - 1] == t -> 0.4f
            else -> 1f
        }
    }

    companion object {
        private const val TRANSPOSE = 0.7f
    }
}
