package dev.continuum.fx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PivotTest {
    private val n = 4 // sound, island, volume, keyboard

    @Test
    fun slotsWrapRoundInBothDirections() {
        assertEquals(0, PivotMath.indexOf(4, n))
        assertEquals(3, PivotMath.indexOf(-1, n))
        assertEquals(2, PivotMath.indexOf(-6, n))
        assertEquals(1, PivotMath.indexOf(9, n))
    }

    @Test
    fun aPageOpenedFromElsewhereIsReachedTheShortWay() {
        // From keyboard (slot 3), sound is one step on (slot 4), not three back.
        assertEquals(4, PivotMath.nearest(0, 3, n))
        // From sound (slot 0), keyboard is one step back.
        assertEquals(-1, PivotMath.nearest(3, 0, n))
        // Already there.
        assertEquals(6, PivotMath.nearest(2, 6, n))
        // Two either way: forward.
        assertEquals(6, PivotMath.nearest(2, 4, n))
    }

    @Test
    fun theTitleAtRestAlwaysNamesThePage() {
        // Swipe forward round twice and back round once: the settled slot names the page shown.
        var slot = 0
        val pages = mutableListOf<Int>()
        repeat(9) { slot += 1; pages += PivotMath.indexOf(slot, n) }
        repeat(5) { slot -= 1; pages += PivotMath.indexOf(slot, n) }
        assertEquals(listOf(1, 2, 3, 0, 1, 2, 3, 0, 1, 0, 3, 2, 1, 0), pages)
    }
}
