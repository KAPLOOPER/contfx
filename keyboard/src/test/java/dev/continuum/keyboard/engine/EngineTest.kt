package dev.continuum.keyboard.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    companion object {
        private val main: Words by lazy { Words.read(File("src/main/assets/words/en_words.txt").bufferedReader()) }
        private val next: NextWords by lazy { NextWords.read(File("src/main/assets/words/en_next.txt").bufferedReader()) }
        private val keys = KeyMap.qwerty()

        fun suggester(): Suggester = Suggester(main, next, Learned()).also { it.keys = keys }

        /** Typed with a touch on the centre of each key, nudged by [nudge] keys. */
        fun tapped(text: String, nudge: Map<Int, Pair<Float, Float>> = emptyMap()): Typed {
            val key = Words.keyOf(text)
            val xs = FloatArray(key.length)
            val ys = FloatArray(key.length)
            for ((i, c) in key.withIndex()) {
                val k = keys.centre(c)
                val (nx, ny) = nudge[i] ?: (0f to 0f)
                xs[i] = if (k == null) Float.NaN else k[0] + nx * keys.keyWidth
                ys[i] = if (k == null) Float.NaN else k[1] + ny * keys.keyHeight
            }
            return Typed(text, xs, ys)
        }
    }

    @Test
    fun wordsLoadAndLookUp() {
        assertTrue(main.size > 100_000)
        assertTrue(main.contains("the"))
        assertTrue(main.contains("don't"))
        assertEquals("London", main.bestForm("london"))
        assertEquals("I", main.bestForm("i"))
        assertEquals("may", main.bestForm("may"))
        assertTrue(main.contains("café") == main.contains("cafe"))
    }

    @Test
    fun correctsCommonTypos() {
        val s = suggester()
        val cases = mapOf(
            "teh" to "the",
            "recieve" to "receive",
            "dont" to "don't",
            "helo" to "hello",
            "thsi" to "this",
            "wierd" to "weird",
            "tommorow" to "tomorrow",
            "definately" to "definitely",
            "becuase" to "because",
            "youre" to "you're",
            "beautful" to "beautiful",
            "acommodate" to "accommodate",
        )
        val misses = ArrayList<String>()
        for ((typo, want) in cases) {
            val r = s.suggest(tapped(typo), emptyList(), Caps.Lower, autocorrect = true)
            if (r.correction != want) misses += "$typo -> ${r.correction} ${r.words}"
        }
        assertTrue("misses: $misses", misses.isEmpty())
    }

    @Test
    fun slipsOntoNeighbouringKeys() {
        val s = suggester()
        // "hrllo": the r was a slip from e, touched near the edge between them.
        val r = s.suggest(tapped("hrllo", mapOf(1 to (-0.45f to 0f))), emptyList(), Caps.Lower, autocorrect = true)
        assertEquals("hello", r.correction)
        // "im" with a clean tap on m is "I'm", not "in".
        val im = s.suggest(tapped("im"), emptyList(), Caps.Lower, autocorrect = true)
        assertEquals("I'm", im.correction)
    }

    @Test
    fun leavesRealWordsAlone() {
        val s = suggester()
        for (w in listOf("its", "were", "well", "ill", "form", "from", "lol", "ok")) {
            val r = s.suggest(tapped(w), emptyList(), Caps.Lower, autocorrect = true)
            assertEquals("$w: ${r.words}", null, r.correction)
            assertTrue(r.typedIsWord)
        }
    }

    @Test
    fun fixesCapitals() {
        val s = suggester()
        assertEquals("I", s.suggest(tapped("i"), emptyList(), Caps.Lower, true).correction)
        assertEquals("London", s.suggest(tapped("london"), emptyList(), Caps.Lower, true).correction)
        assertEquals("Teh fix keeps capitals", "The", s.suggest(tapped("Teh"), emptyList(), Caps.First, true).correction)
    }

    @Test
    fun completesWords() {
        val s = suggester()
        val r = s.suggest(tapped("beaut"), emptyList(), Caps.Lower, true)
        assertTrue("${r.words}", r.words.any { it == "beautiful" || it == "beauty" })
        assertEquals(null, r.correction)
        val t = s.suggest(tapped("tomor"), emptyList(), Caps.Lower, true)
        assertTrue("${t.words}", "tomorrow" in t.words)
    }

    @Test
    fun usesContext() {
        val s = suggester()
        assertEquals("you", s.predict(listOf("thank"), false).first())
        assertEquals("to", s.predict(listOf("going"), false).first())
        assertEquals("I", s.predict(emptyList(), true).first())
    }

    @Test
    fun learnsNewWords() {
        val learned = Learned()
        val s = Suggester(main, next, learned).also { it.keys = keys }
        val word = "zorbly"
        assertTrue(s.suggest(tapped(word), emptyList(), Caps.Lower, true).let { !it.typedIsWord })
        learned.use(word, null)
        learned.use(word, null)
        assertTrue(s.suggest(tapped(word), emptyList(), Caps.Lower, true).typedIsWord)
        // A slip on a learned word is corrected to it.
        val r = s.suggest(tapped("zorbky"), emptyList(), Caps.Lower, true)
        assertEquals("zorbly", r.correction)
        learned.forget(word)
        assertTrue(!s.suggest(tapped(word), emptyList(), Caps.Lower, true).typedIsWord)
    }

    @Test
    fun learnedWordsRoundTrip() {
        val a = Learned()
        a.use("zorbly", null); a.use("zorbly", "oh"); a.insist("Zed")
        val sw = java.io.StringWriter()
        a.write(sw)
        val b = Learned()
        b.read(sw.toString().reader().buffered())
        assertEquals(1, b.pairCount("oh", "zorbly"))
        assertTrue(b.words(main).contains("zorbly"))
    }

    @Test
    fun deleteSwipeTakesWholeWords() {
        val t = "Hello there, my friend  "
        assertEquals("friend  ".length, TextRules.charsForWords(t, 1))
        assertEquals("my friend  ".length, TextRules.charsForWords(t, 2))
        assertEquals(", my friend  ".length, TextRules.charsForWords(t, 3))
        assertEquals(t.length, TextRules.charsForWords(t, 9))
        assertEquals("friend", TextRules.wordBefore("my friend"))
        assertEquals(listOf("my", "friend"), TextRules.contextWords("Hello there. my friend "))
        assertTrue(TextRules.atSentenceStart("Hi. "))
        assertTrue(!TextRules.atSentenceStart("Hi"))
    }
}
