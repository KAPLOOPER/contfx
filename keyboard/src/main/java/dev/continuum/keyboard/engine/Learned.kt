package dev.continuum.keyboard.engine

import java.io.BufferedReader
import java.io.Writer
import kotlin.math.ln
import kotlin.math.min

/**
 * What this keyboard has picked up from you: how often you use each word, new words you
 * keep typing, and which words you put together. It never leaves the phone.
 */
class Learned {
    /** Uses of each word, by the way you write it. */
    private val uses = HashMap<String, Int>()
    /** Uses of each pair of words, keyed "first\u0001second" in lower case. */
    private val pairs = HashMap<String, Int>()
    /** Words you've told it to stop suggesting. */
    private val blocked = HashSet<String>()

    private var learnedWords: Words? = null
    var changed = false
        private set

    /** Words not in the main list that you've used enough to count as words. */
    @Synchronized
    fun words(main: Words): Words {
        learnedWords?.let { return it }
        val list = ArrayList<String>()
        val freqs = ArrayList<Int>()
        for ((w, n) in uses) {
            if (n < LEARN_AFTER || main.contains(w) || Words.keyOf(w) in blocked) continue
            list += w
            freqs += learnedFrequency(n)
        }
        return Words.build(list, freqs).also { learnedWords = it }
    }

    /** You used [word]. [known] says whether it's already in the main list. */
    @Synchronized
    fun use(word: String, previous: String?) {
        if (word.isEmpty() || word.length > 40) return
        uses[word] = (uses[word] ?: 0) + 1
        if (previous != null && previous.isNotEmpty()) {
            val key = previous.lowercase() + SEP + word.lowercase()
            pairs[key] = (pairs[key] ?: 0) + 1
        }
        blocked.remove(Words.keyOf(word))
        learnedWords = null
        changed = true
        trim()
    }

    /** You insisted on [word] (undid a correction, or picked it as typed): learn it now. */
    @Synchronized
    fun insist(word: String) {
        if (word.isEmpty() || word.length > 40) return
        uses[word] = maxOf(uses[word] ?: 0, LEARN_AFTER)
        blocked.remove(Words.keyOf(word))
        learnedWords = null
        changed = true
    }

    /** Stop suggesting [word]. */
    @Synchronized
    fun forget(word: String) {
        val key = Words.keyOf(word)
        uses.keys.removeAll { Words.keyOf(it) == key }
        blocked += key
        learnedWords = null
        changed = true
    }

    @Synchronized
    fun clear() {
        uses.clear(); pairs.clear(); blocked.clear()
        learnedWords = null
        changed = true
    }

    fun isBlocked(word: String): Boolean = Words.keyOf(word) in blocked

    /** A small lift for words you use often, in Zipf units. */
    @Synchronized
    fun boost(word: String): Float {
        val n = uses[word] ?: return 0f
        return min(1.2f, 0.25f * ln(1f + n))
    }

    /** How often you've followed [previous] with [word]. */
    @Synchronized
    fun pairCount(previous: String, word: String): Int = pairs[previous.lowercase() + SEP + word.lowercase()] ?: 0

    /** Words you've put after [previous], most used first. */
    @Synchronized
    fun after(previous: String): List<Pair<String, Int>> {
        val prefix = previous.lowercase() + SEP
        return pairs.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.key.substring(prefix.length) to it.value }
            .sortedByDescending { it.second }
            .take(8)
            .toList()
    }

    val wordCount: Int @Synchronized get() = uses.count { it.value >= LEARN_AFTER }

    private fun trim() {
        if (uses.size > MAX_WORDS) uses.entries.removeAll { it.value <= 1 }
        if (pairs.size > MAX_PAIRS) pairs.entries.removeAll { it.value <= 1 }
    }

    @Synchronized
    fun write(out: Writer) {
        out.write("# Continuum Keyboard learned words v1\n")
        for ((w, n) in uses) out.write("w\t$w\t$n\n")
        for ((k, n) in pairs) {
            val i = k.indexOf(SEP)
            out.write("p\t${k.substring(0, i)}\t${k.substring(i + 1)}\t$n\n")
        }
        for (b in blocked) out.write("b\t$b\n")
        changed = false
    }

    @Synchronized
    fun read(reader: BufferedReader) {
        reader.useLines { lines ->
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                val parts = line.split('\t')
                when (parts[0]) {
                    "w" -> if (parts.size == 3) uses[parts[1]] = parts[2].toIntOrNull() ?: continue
                    "p" -> if (parts.size == 4) pairs[parts[1] + SEP + parts[2]] = parts[3].toIntOrNull() ?: continue
                    "b" -> if (parts.size == 2) blocked += parts[1]
                }
            }
        }
        learnedWords = null
        changed = false
    }

    companion object {
        const val LEARN_AFTER = 2
        private const val SEP = '\u0001'
        private const val MAX_WORDS = 40_000
        private const val MAX_PAIRS = 60_000

        /** Zipf x10 for a learned word used [n] times. */
        fun learnedFrequency(n: Int): Int = (36 + 4 * min(n, 6))
    }
}
