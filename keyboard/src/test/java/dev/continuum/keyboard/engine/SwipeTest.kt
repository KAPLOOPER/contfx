package dev.continuum.keyboard.engine

import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/** Draws wobbly swipes through words' letters and checks the decoder reads them back. */
class SwipeTest {

    private val keys = KeyMap.qwerty()
    private val decoder by lazy { SwipeDecoder(EngineTest.suggester().main) }

    /** A swipe through [word]'s keys: each corner missed a little, the line drawn with a wobble. */
    fun draw(word: String, rnd: Random, miss: Float = 0.22f): Pair<FloatArray, FloatArray> {
        val path = SwipeDecoder.pathOf(word)!!
        val cx = ArrayList<Float>()
        val cy = ArrayList<Float>()
        for (c in path) {
            val k = keys.centre(c)!!
            cx += k[0] + (rnd.nextGaussian() * miss).toFloat() * keys.keyWidth
            cy += k[1] + (rnd.nextGaussian() * miss * 0.7f).toFloat() * keys.keyHeight
        }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until cx.size - 1) {
            val steps = 12
            // A slight bow on each stroke, like a thumb.
            val bow = (rnd.nextGaussian() * 0.15).toFloat()
            val nx = -(cy[i + 1] - cy[i])
            val ny = cx[i + 1] - cx[i]
            for (s in 0 until steps) {
                val t = s / steps.toFloat()
                val b = bow * 4 * t * (1 - t)
                xs += cx[i] + (cx[i + 1] - cx[i]) * t + nx * b
                ys += cy[i] + (cy[i + 1] - cy[i]) * t + ny * b
            }
        }
        xs += cx.last(); ys += cy.last()
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test
    fun readsSwipes() {
        val rnd = Random(11)
        val s = EngineTest.suggester()
        for ((range, need) in listOf((0 to 5000) to 0.80, (5000 to 30000) to 0.60)) {
            var top1 = 0
            var top3 = 0
            var total = 0
            val started = System.nanoTime()
            val misses = ArrayList<String>()
            while (total < 200) {
                val w = s.main.word(range.first + rnd.nextInt(range.second - range.first))
                if (w.length < 2 || !w.all { it in 'a'..'z' }) continue
                total++
                val (xs, ys) = draw(w, rnd)
                val out = decoder.decode(xs, ys, keys)
                if (out.firstOrNull()?.word == w) top1++ else if (misses.size < 12) misses += "$w→${out.take(3).map { it.word }}"
                if (out.take(3).any { it.word == w }) top3++
            }
            val ms = (System.nanoTime() - started) / 1e6 / total
            println("swipe ${range.first}-${range.second}: top1 ${top1 * 100 / total}% top3 ${top3 * 100 / total}% ${"%.1f".format(ms)} ms; $misses")
            assertTrue("top1 $top1/$total", top1.toDouble() / total >= need)
        }
    }
}
