package dev.continuum.keyboard.engine

import kotlin.math.sqrt

/**
 * Where each letter sits on the keyboard, so corrections can tell a near miss from a
 * real typo and swipes can be matched to words. Positions are in pixels.
 */
class KeyMap(
    /** Centres by lower-case letter. */
    private val centres: Map<Char, FloatArray>,
    val keyWidth: Float,
    val keyHeight: Float,
) {
    fun centre(c: Char): FloatArray? = centres[c]

    val letters: Set<Char> get() = centres.keys

    /** Distance from (x, y) to [c]'s centre, measured in keys. */
    fun distance(c: Char, x: Float, y: Float): Float {
        val k = centres[c] ?: return Float.MAX_VALUE
        val dx = (x - k[0]) / keyWidth
        val dy = (y - k[1]) / keyHeight
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance between two keys, measured in keys. */
    fun between(a: Char, b: Char): Float {
        val k = centres[b] ?: return Float.MAX_VALUE
        return distance(a, k[0], k[1])
    }

    /** The letter whose key is nearest to (x, y). */
    fun nearest(x: Float, y: Float): Char? = centres.keys.minByOrNull { distance(it, x, y) }

    /** Letters within [radius] keys of (x, y), nearest first. */
    fun near(x: Float, y: Float, radius: Float): List<Char> =
        centres.keys.map { it to distance(it, x, y) }.filter { it.second <= radius }.sortedBy { it.second }.map { it.first }

    companion object {
        /**
         * A standard QWERTY map with unit keys, for tests and for re-reading words that
         * were typed before the keyboard opened.
         */
        fun qwerty(keyWidth: Float = 100f, keyHeight: Float = 150f): KeyMap {
            val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val offsets = listOf(0f, 0.5f, 1.5f)
            val m = HashMap<Char, FloatArray>()
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { i, c ->
                    m[c] = floatArrayOf((offsets[r] + i + 0.5f) * keyWidth, (r + 0.5f) * keyHeight)
                }
            }
            return KeyMap(m, keyWidth, keyHeight)
        }
    }
}
