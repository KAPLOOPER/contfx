package dev.continuum.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {

    private fun letters(numberRow: Boolean = false) = Layouts.place(
        Sheet.Letters,
        Layouts.rows(Sheet.Letters, FieldMode.Text, "send", numberRow),
        width = 1080f, rowPitch = 155f, keyHeight = 127f,
    )

    @Test
    fun everyKeyIsFoundAtItsCentre() {
        for (sheet in Sheet.values()) {
            val l = Layouts.place(sheet, Layouts.rows(sheet, FieldMode.Text, "send", false), 1080f, 155f, 127f)
            for (k in l.keys) assertTrue("$sheet ${k.key.label}", l.keyAt(k.cx, k.cy) === k)
            for (k in l.keys) {
                assertTrue("${k.key.label} inside", k.x >= 0f && k.right <= 1080.5f)
            }
        }
    }

    @Test
    fun lettersMatchTheDesign() {
        val l = letters()
        assertEquals(26 + 2 + 5, l.keys.size)
        val q = l.keys.first { it.key.text == "q" }
        val p = l.keys.first { it.key.text == "p" }
        // Ten letters and their gaps span the width less the side margins.
        assertEquals(1080f - 2 * 4.8f * 1080f / 390f, p.right - q.x, 1.0f.toDouble().toFloat())
        val space = l.keys.first { it.key.type == KeyType.Space }
        assertTrue(space.w > 3 * q.w)
        // Gaps belong to the nearest key.
        val a = l.keys.first { it.key.text == "a" }
        val s = l.keys.first { it.key.text == "s" }
        assertEquals("a", l.keyAt((a.right + s.x) / 2f - 1f, a.cy)!!.key.text)
        assertEquals("s", l.keyAt((a.right + s.x) / 2f + 1f, a.cy)!!.key.text)
        val km = l.keyMap()!!
        assertTrue(km.between('q', 'w') in 0.9f..1.1f)
    }

    @Test
    fun numberRowSitsAboveLetters() {
        val l = letters(numberRow = true)
        val one = l.keys.first { it.key.text == "1" }
        val q = l.keys.first { it.key.text == "q" }
        assertTrue(one.y < q.y)
        assertTrue(q.key.popup.firstOrNull() != "1")
        assertEquals("1", letters().keys.first { it.key.text == "q" }.key.popup.first())
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
}
