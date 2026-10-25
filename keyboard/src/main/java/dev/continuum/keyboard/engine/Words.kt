package dev.continuum.keyboard.engine

import java.io.BufferedReader
import java.text.Normalizer

/**
 * A word list packed into a trie of flat arrays, so a hundred thousand words cost a few
 * megabytes and can be walked quickly while correcting or completing what's typed.
 *
 * Words are found by their key: lower case, accents removed, curly apostrophes made
 * straight. Several words can share a key ("may" and "May", "cafe" and "café").
 */
class Words private constructor(
    /** Each word as it's written. */
    private val text: Array<String>,
    /** Each word's frequency: Zipf x10, so 0..~80. */
    private val freq: ByteArray,
    private val ch: CharArray,
    private val firstChild: IntArray,
    private val nextSibling: IntArray,
    /** First word ending at each node, or -1. */
    private val terminal: IntArray,
    /** The next word with the same key, or -1. */
    private val sameKey: IntArray,
    /** Highest frequency anywhere below each node. */
    private val maxFreq: ByteArray,
) {
    val size: Int get() = text.size

    fun word(i: Int): String = text[i]
    fun frequency(i: Int): Int = freq[i].toInt()

    /** Words in order of frequency, highest first (the order they were given in). */
    fun indices(): IntRange = text.indices

    internal val root: Int get() = 0
    internal fun char(node: Int): Char = ch[node]
    internal fun firstChild(node: Int): Int = firstChild[node]
    internal fun nextSibling(node: Int): Int = nextSibling[node]
    internal fun terminal(node: Int): Int = terminal[node]
    internal fun sameKey(word: Int): Int = sameKey[word]
    internal fun maxFreq(node: Int): Int = maxFreq[node].toInt()

    /** The node reached by [key], or -1. */
    internal fun find(key: CharSequence): Int {
        var node = 0
        for (c in key) {
            var child = firstChild[node]
            while (child != -1 && ch[child] != c) child = nextSibling[child]
            if (child == -1) return -1
            node = child
        }
        return node
    }

    /** Every word whose key is exactly [key]. */
    fun lookup(key: CharSequence): List<Int> {
        val node = find(key)
        if (node == -1) return emptyList()
        val out = ArrayList<Int>(2)
        var w = terminal[node]
        while (w != -1) {
            out += w
            w = sameKey[w]
        }
        return out
    }

    /** True when [word] is in the list, ignoring case and accents. */
    fun contains(word: String): Boolean = lookup(keyOf(word)).isNotEmpty()

    /** The most frequent spelling of [word] (so "london" gives "London"), or null. */
    fun bestForm(word: String): String? = lookup(keyOf(word)).maxByOrNull { freq[it] }?.let { text[it] }

    /** The frequency of [word] (Zipf x10), or 0 when it isn't known. */
    fun frequencyOf(word: String): Int = lookup(keyOf(word)).maxOfOrNull { freq[it].toInt() } ?: 0

    companion object {
        /** The search key for [word]. */
        fun keyOf(word: CharSequence): String {
            val sb = StringBuilder(word.length)
            var plain = true
            for (c in word) if (c.code > 0x7F) { plain = false; break }
            val source = if (plain) word else Normalizer.normalize(word, Normalizer.Form.NFD)
            for (c in source) {
                when {
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                    c == '’' || c == '‘' -> sb.append('\'')
                    else -> sb.append(c.lowercaseChar())
                }
            }
            return sb.toString()
        }

        /** Reads "word<TAB>frequency" lines, most frequent first. Lines starting with # are skipped. */
        fun read(reader: BufferedReader, limit: Int = Int.MAX_VALUE): Words {
            val words = ArrayList<String>(120_000)
            val freqs = ArrayList<Int>(120_000)
            reader.useLines { lines ->
                for (line in lines) {
                    if (words.size >= limit) break
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    words += line.substring(0, tab)
                    freqs += line.substring(tab + 1).trim().toIntOrNull() ?: 0
                }
            }
            return build(words, freqs)
        }

        /** Builds from parallel lists. Frequencies are clamped to 0..120. */
        fun build(words: List<String>, freqs: List<Int>): Words {
            val n = words.size
            val keys = Array(n) { keyOf(words[it]) }
            val order = (0 until n).sortedWith { a, b -> keys[a].compareTo(keys[b]) }

            var cap = 1024
            var ch = CharArray(cap)
            var first = IntArray(cap)
            var next = IntArray(cap)
            var last = IntArray(cap)
            var term = IntArray(cap)
            var parent = IntArray(cap)
            var count = 1
            first[0] = -1; next[0] = -1; last[0] = -1; term[0] = -1; parent[0] = -1
            val same = IntArray(n) { -1 }
            val fr = ByteArray(n) { freqs[it].coerceIn(0, 120).toByte() }

            for (w in order) {
                var node = 0
                for (c in keys[w]) {
                    val lc = last[node]
                    if (lc != -1 && ch[lc] == c) {
                        node = lc
                        continue
                    }
                    if (count == cap) {
                        cap *= 2
                        ch = ch.copyOf(cap); first = first.copyOf(cap); next = next.copyOf(cap)
                        last = last.copyOf(cap); term = term.copyOf(cap); parent = parent.copyOf(cap)
                    }
                    val made = count++
                    ch[made] = c; first[made] = -1; next[made] = -1; last[made] = -1; term[made] = -1
                    parent[made] = node
                    if (lc == -1) first[node] = made else next[lc] = made
                    last[node] = made
                    node = made
                }
                // Keep each key's words with the most frequent first.
                val head = term[node]
                if (head == -1 || fr[w] > fr[head]) {
                    same[w] = head
                    term[node] = w
                } else {
                    var at = head
                    while (same[at] != -1 && fr[same[at]] >= fr[w]) at = same[at]
                    same[w] = same[at]
                    same[at] = w
                }
            }

            val best = ByteArray(count)
            for (node in 0 until count) {
                var w = term[node]
                while (w != -1) {
                    if (fr[w] > best[node]) best[node] = fr[w]
                    w = same[w]
                }
            }
            // Children always come after their parent, so one backwards pass fills the maxima.
            for (node in count - 1 downTo 1) {
                val p = parent[node]
                if (best[node] > best[p]) best[p] = best[node]
            }

            return Words(
                text = words.toTypedArray(),
                freq = fr,
                ch = ch.copyOf(count),
                firstChild = first.copyOf(count),
                nextSibling = next.copyOf(count),
                terminal = term.copyOf(count),
                sameKey = same,
                maxFreq = best,
            )
        }

        val Empty: Words = build(emptyList(), emptyList())
    }
}
