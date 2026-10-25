package dev.continuum.keyboard.engine

import java.io.BufferedReader

/** Which words tend to follow which, from "word<TAB>next<TAB>score" lines (score = log10 P x100). */
class NextWords private constructor(private val table: Map<String, List<Pair<String, Int>>>) {

    /** Likely next words after [word] (lower case), most likely first. */
    fun after(word: String): List<Pair<String, Int>> = table[word.lowercase()] ?: emptyList()

    /** log10 P(next | word) x100, or null when the pair isn't known. */
    fun score(word: String, next: String): Int? {
        val list = table[word.lowercase()] ?: return null
        val n = next.lowercase()
        for ((w, s) in list) if (w == n) return s
        return null
    }

    val size: Int get() = table.size

    companion object {
        fun read(reader: BufferedReader): NextWords {
            val map = HashMap<String, MutableList<Pair<String, Int>>>(20_000)
            reader.useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val a = line.indexOf('\t')
                    val b = line.indexOf('\t', a + 1)
                    if (a <= 0 || b <= a) continue
                    val word = line.substring(0, a)
                    val next = line.substring(a + 1, b)
                    val score = line.substring(b + 1).trim().toIntOrNull() ?: continue
                    map.getOrPut(word) { ArrayList(8) } += next to score
                }
            }
            return NextWords(map)
        }

        val Empty = NextWords(emptyMap())
    }
}
