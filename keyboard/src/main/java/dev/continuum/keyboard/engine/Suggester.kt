package dev.continuum.keyboard.engine

import kotlin.math.max
import kotlin.math.min

/** How the word being typed is capitalised. */
enum class Caps { Lower, First, All }

/** The strip above the keys, worked out for one moment of typing. */
data class Suggestions(
    /** Up to three words for the strip, left to right. */
    val words: List<String>,
    /** Which of [words] a space will commit, or -1 to keep what was typed. */
    val commitIndex: Int,
    /** What was typed, so the strip can show it in quotes. */
    val typed: String,
    /** Index in [words] of the typed word shown as typed, or -1. */
    val typedIndex: Int,
    /** True when what was typed is already a word. */
    val typedIsWord: Boolean,
) {
    /** The word a space will commit instead of what was typed, or null. */
    val correction: String? get() = if (commitIndex >= 0 && commitIndex != typedIndex) words[commitIndex] else null

    companion object {
        val None = Suggestions(emptyList(), -1, "", -1, false)
    }
}

/**
 * Puts the word list, next-word table and what's been learned together: corrections and
 * completions while you type, predictions after a space, and the word a space commits.
 */
class Suggester(
    val main: Words,
    val next: NextWords,
    val learned: Learned,
) {
    var keys: KeyMap? = null
        set(value) {
            field = value
            corrector = Corrector(value)
        }
    private var corrector = Corrector(null)

    /** Suggestions for [typed], after the words in [before] (most recent last). */
    fun suggest(typed: Typed, before: List<String>, caps: Caps, autocorrect: Boolean): Suggestions {
        val n = typed.length
        if (n == 0) return Suggestions.None
        val prev = before.lastOrNull()
        val letters = typed.key.all { it.isLetter() || it == '\'' || it == '-' }

        val maxCost = when {
            n <= 2 -> 1.0f
            n <= 4 -> 1.6f
            n <= 7 -> 2.2f
            else -> 2.8f
        }
        val user = learned.words(main)
        val found = ArrayList<Candidate>()
        found += corrector.search(main, typed, maxCost, completions = true)
        if (user.size > 0) found += corrector.search(user, typed, maxCost, completions = true, learned = true)

        val typedKey = typed.key
        val typedIsWord = (main.lookup(typedKey).isNotEmpty() || user.lookup(typedKey).isNotEmpty()) &&
            !learned.isBlocked(typed.text)

        class Scored(val word: String, val score: Float, val cand: Candidate)
        val best = HashMap<String, Scored>()
        for (c in found) {
            if (learned.isBlocked(c.word)) continue
            val extra = if (c.completion) max(0, Words.keyOf(c.word).length - n) else 0
            var s = c.freq / 10f + learned.boost(c.word) - 3.2f * c.cost
            if (c.completion) s -= 0.9f + 0.06f * extra
            if (prev != null) s += context(prev, c.word)
            val key = Words.keyOf(c.word)
            val had = best[key]
            if (had == null || s > had.score) best[key] = Scored(c.word, s, c)
        }
        val ranked = best.values.sortedByDescending { it.score }

        // What a space commits: the typed word when it's a word (with its usual capitals),
        // otherwise the best close correction if it's convincing enough.
        val typedForm: String = if (typedIsWord) {
            val form = main.bestForm(typed.text) ?: user.bestForm(typed.text) ?: typed.text
            if (caps == Caps.Lower && form.any { it.isUpperCase() }) form else typed.text
        } else typed.text

        var correction: String? = null
        if (autocorrect && letters && !typedIsWord) {
            // Longer words can carry more slips; a long string that isn't a word is rarely meant.
            val bound = when {
                n <= 2 -> 0.75f
                n <= 4 -> 1.3f
                n <= 7 -> 2.2f
                else -> 2.8f
            }
            val floor = when {
                n <= 3 -> 1.5f
                n <= 5 -> 0.8f
                else -> 0f
            }
            val pick = ranked.firstOrNull { !it.cand.completion && it.cand.cost <= bound && it.score >= floor }
            if (pick != null) correction = recase(pick.word, caps)
        } else if (autocorrect && typedIsWord && typedForm != typed.text) {
            correction = typedForm
        }

        val alternatives = ranked.asSequence()
            .map { recase(it.word, caps) }
            .filter { !it.equals(typed.text, ignoreCase = false) && it != correction && Words.keyOf(it) != typedKey }
            .distinct()
            .toList()

        val words = ArrayList<String>(3)
        val commitIndex: Int
        val typedIndex: Int
        when {
            correction != null && !typedIsWord -> {
                words += typed.text; words += correction
                alternatives.firstOrNull()?.let { words += it }
                typedIndex = 0; commitIndex = 1
            }
            correction != null -> {
                // Only the capitals change ("london" to "London").
                alternatives.firstOrNull()?.let { words += it }
                words += correction
                alternatives.getOrNull(1)?.let { words += it }
                typedIndex = -1; commitIndex = words.indexOf(correction)
            }
            typedIsWord -> {
                alternatives.firstOrNull()?.let { words += it }
                words += typedForm
                alternatives.getOrNull(1)?.let { words += it }
                typedIndex = words.indexOf(typedForm); commitIndex = typedIndex
            }
            else -> {
                words += typed.text
                alternatives.take(2).forEach { words += it }
                typedIndex = 0; commitIndex = 0
            }
        }
        return Suggestions(words, commitIndex, typed.text, typedIndex, typedIsWord)
    }

    /** Likely next words after [before]; [sentenceStart] capitalises them. */
    fun predict(before: List<String>, sentenceStart: Boolean, limit: Int = 3): List<String> {
        val prev = before.lastOrNull()
        val scores = HashMap<String, Float>()
        if (prev == null || sentenceStart && prev.isEmpty()) {
            for ((i, w) in STARTERS.withIndex()) scores[w] = 3f - i * 0.1f
        } else {
            for ((w, s) in next.after(prev)) scores[w] = (scores[w] ?: 0f) + 2.5f + s / 100f
            for ((w, n) in learned.after(prev)) scores[w] = (scores[w] ?: 0f) + 1.5f + 0.4f * min(n, 6)
        }
        return scores.entries
            .filter { !learned.isBlocked(it.key) }
            .sortedByDescending { it.value }
            .asSequence()
            .map { main.bestForm(it.key) ?: it.key }
            .map { if (sentenceStart) capitalise(it) else it }
            .distinct()
            .take(limit)
            .toList()
    }

    /** How much [word] fits after [prev], in Zipf units. */
    fun context(prev: String, word: String): Float {
        var bonus = 0f
        next.score(prev, word)?.let { bonus += max(0f, 2.0f + it / 100f) * 0.7f }
        val n = learned.pairCount(prev, word)
        if (n > 0) bonus += 0.8f + 0.3f * min(n, 5)
        return bonus
    }

    companion object {
        val STARTERS = listOf("I", "The", "Thanks", "Hi", "Yes", "No", "It", "What")

        fun capsOf(text: String): Caps = when {
            text.length > 1 && text.all { !it.isLetter() || it.isUpperCase() } && text.any { it.isLetter() } -> Caps.All
            text.firstOrNull()?.isUpperCase() == true -> Caps.First
            else -> Caps.Lower
        }

        fun recase(word: String, caps: Caps): String = when (caps) {
            Caps.Lower -> word
            Caps.First -> capitalise(word)
            Caps.All -> word.uppercase()
        }

        fun capitalise(word: String): String =
            if (word.isEmpty() || word[0].isUpperCase()) word else word[0].titlecase() + word.substring(1)
    }
}
