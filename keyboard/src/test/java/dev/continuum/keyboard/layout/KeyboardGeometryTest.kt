package dev.continuum.keyboard.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardGeometryTest {
    @Test
    fun coldStartRequestMatchesDrawnStripInsteadOfKeyHeightPreference() {
        val width = 1080
        val density = 3f
        val unit = KeyboardGeometry.verticalUnit(width, density, landscape = false)
        assertEquals(1080f / Key.WIDTH, unit, 0.00001f)
        val actualStrip = (44f * unit).toInt()
        assertEquals(actualStrip, KeyboardGeometry.stripHeightPx(width, density, landscape = false))
        // Previously the fallback requested 44 * density * the key-height setting. The strip
        // doesn't use that setting, and on this screen that could overrun the actual strip.
        for (heightScale in listOf(0.88f, 1f, 1.12f)) {
            assertTrue((44 * density * heightScale).toInt() != actualStrip)
        }
    }

    @Test
    fun landscapeUsesTheSameReductionAsDrawnKeyboard() {
        val portrait = KeyboardGeometry.verticalUnit(1080, 3f, landscape = false)
        val landscape = KeyboardGeometry.verticalUnit(1080, 3f, landscape = true)
        assertEquals(portrait * 0.78f, landscape, 0.00001f)
        assertEquals((44 * landscape).toInt(), KeyboardGeometry.stripHeightPx(1080, 3f, landscape = true))
    }

    @Test
    fun wideScreensApplyTheSameDensityCap() {
        assertEquals(3.36f, KeyboardGeometry.verticalUnit(2400, 3f, landscape = false), 0.00001f)
        assertEquals((44 * 3.36f).toInt(), KeyboardGeometry.stripHeightPx(2400, 3f, landscape = false))
    }

    @Test
    fun aRequestAlwaysHasAPositiveHeight() {
        for (width in listOf(0, 1, 320, 1080, 2400)) {
            assertTrue(KeyboardGeometry.stripHeightPx(width, 3f, landscape = false) > 0)
        }
    }
}
