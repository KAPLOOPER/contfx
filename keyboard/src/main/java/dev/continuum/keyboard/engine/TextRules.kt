package dev.continuum.keyboard.engine

/** Small rules about text around the cursor, kept free of Android so they can be tested. */
object TextRules {

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-' || c == '_'

    /**
     * How many characters before the end of [before] make up [words] whole words, the way
     * delete-swipe takes them: each step takes the spaces before a word and the word, or a
     * run of punctuation.
     */
    fun charsForWords(before: CharSequence, words: Int): Int {
        var i = before.length
        repeat(words) {
            if (i == 0) return before.length
            // Trailing spaces go with the word before them.
            while (i > 0 && before[i - 1].isWhitespace()) i--
            if (i == 0) return before.length
            if (isWordChar(before[i - 1])) {
                while (i > 0 && isWordChar(before[i - 1])) i--
            } else {
                while (i > 0 && !isWordChar(before[i - 1]) && !before[i - 1].isWhitespace()) i--
            }
        }
        return before.length - i
    }

    /** The word touching the end of [before] (letters, digits, apostrophes), or "". */
    fun wordBefore(before: CharSequence): String {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return before.substring(i)
    }

    /** The word touching the start of [after], or "". */
    fun wordAfter(after: CharSequence): String {
        var i = 0
        while (i < after.length && isWordChar(after[i])) i++
        return after.substring(0, i)
    }

    /**
     * The words before the cursor in the current sentence, most recent last, so predictions
     * have something to go on. Empty at the start of a sentence.
     */
    fun contextWords(before: CharSequence, max: Int = 2): List<String> {
        val out = ArrayList<String>(max)
        var i = before.length
        while (out.size < max) {
            while (i > 0 && before[i - 1] == ' ') i--
            if (i == 0) break
            val c = before[i - 1]
            if (c == '.' || c == '!' || c == '?' || c == '\n') break
            if (!isWordChar(c)) break
            val end = i
            while (i > 0 && isWordChar(before[i - 1])) i--
            out.add(0, before.substring(i, end))
        }
        return out
    }

    /** True when the cursor after [before] starts a sentence. */
    fun atSentenceStart(before: CharSequence): Boolean {
        var i = before.length
        while (i > 0 && before[i - 1] == ' ') i--
        if (i == 0) return true
        val c = before[i - 1]
        return (c == '.' || c == '!' || c == '?' || c == '\n') && i < before.length
    }

    /** Punctuation that sits against the word before it, with the space moved after. */
    fun hugsWordBefore(c: Char): Boolean = c in ".,!?;:)]}%…"

    /** Punctuation after which a space comes automatically when typing continues. */
    fun wantsSpaceAfter(c: Char): Boolean = c in ".,!?;:…"
}
