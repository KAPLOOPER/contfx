package dev.continuum.keyboard.engine

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Types common words with sloppy thumbs and checks the keyboard recovers most of them. */
class QualityTest {

    private val keys = KeyMap.qwerty()

    private fun sample(from: Int, to: Int, count: Int, rnd: Random): List<String> {
        val s = EngineTest.suggester()
        val out = ArrayList<String>()
        while (out.size < count) {
            val w = s.main.word(from + rnd.nextInt(to - from))
            if (w.length >= 3 && w.all { it in 'a'..'z' }) out += w
        }
        return out
    }

    private fun sloppy(word: String, rnd: Random, sigma: Float): Typed {
        val xs = FloatArray(word.length)
        val ys = FloatArray(word.length)
        val sb = StringBuilder()
        for ((i, c) in word.withIndex()) {
            val k = keys.centre(c)!!
            val x = k[0] + (rnd.nextGaussian() * sigma).toFloat() * keys.keyWidth
            val y = k[1] + (rnd.nextGaussian() * sigma * 0.8f).toFloat() * keys.keyHeight
            xs[i] = x; ys[i] = y
            sb.append(keys.nearest(x, y))
        }
        return Typed(sb.toString(), xs, ys)
    }

    @Test
    fun recoversSloppyTyping() {
        val rnd = Random(7)
        val s = EngineTest.suggester()
        for ((range, need) in listOf((0 to 3000) to 0.80, (3000 to 20000) to 0.60)) {
            val words = sample(range.first, range.second, 300, rnd)
            var typo = 0
            var fixed = 0
            var inStrip = 0
            var started = System.nanoTime()
            for (w in words) {
                val t = sloppy(w, rnd, 0.33f)
                if (t.text == w) continue
                typo++
                val r = s.suggest(t, emptyList(), Caps.Lower, true)
                val committed = r.words.getOrNull(r.commitIndex)
                if (committed == w) fixed++ else if (System.getenv("SHOW") != null) println("  $w typed ${t.text} word=${r.typedIsWord} -> $committed ${r.words}")
                if (w in r.words) inStrip++
            }
            val ms = (System.nanoTime() - started) / 1e6 / words.size
            val rate = fixed.toDouble() / typo
            println("words ${range.first}-${range.second}: $typo typos, fixed ${"%.2f".format(rate)}, in strip ${"%.2f".format(inStrip.toDouble() / typo)}, ${"%.1f".format(ms)} ms each")
            assertTrue("fix rate $rate", rate >= need)
        }
    }

    @Test
    fun leavesCorrectWordsAlone() {
        val rnd = Random(3)
        val s = EngineTest.suggester()
        val words = sample(0, 30000, 400, rnd)
        val changed = words.filter { w ->
            val k = w.map { keys.centre(it)!! }
            val r = s.suggest(Typed(w, FloatArray(w.length) { k[it][0] }, FloatArray(w.length) { k[it][1] }), emptyList(), Caps.Lower, true)
            r.correction != null && r.correction?.lowercase() != w
        }
        println("changed correct words: $changed")
        assertTrue("$changed", changed.isEmpty())
    }
}
