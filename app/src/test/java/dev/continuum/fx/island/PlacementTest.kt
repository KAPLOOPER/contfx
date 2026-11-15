package dev.continuum.fx.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementTest {
    @Test
    fun fineNudgesNearTheDefaultAreNotSwallowedBySliderSnapping() {
        for (n in -8..8) {
            assertEquals(n * 0.25f, placementStep(n * 0.25f, -60f, 60f, 0.25f), 0f)
        }
        assertEquals(0.99f, placementStep(1f - 0.01f, 0.4f, 1.6f, 0.01f), 0.00001f)
        assertEquals(1.01f, placementStep(1f + 0.01f, 0.4f, 1.6f, 0.01f), 0.00001f)
    }

    @Test
    fun repeatedSteppingStaysWithinTheAllowedRange() {
        var value = 0f
        repeat(1000) { value = placementStep(value + 0.25f, -8f, 64f, 0.25f) }
        assertEquals(64f, value, 0f)
        repeat(1000) { value = placementStep(value - 0.25f, -8f, 64f, 0.25f) }
        assertEquals(-8f, value, 0f)
    }

    @Test
    fun resetClearsAllPlacementButKeepsSourceAndClockChoices() {
        val original = IslandPrefs(enabled = false, media = false, timeSpot = IslandPrefs.TIME_SPLIT)
        val placed = original.copy(
            offsetX = 0.25f, offsetY = -0.25f, cameraSize = -4f, scale = 1.1f,
            starWidth = 0.7f, starHeight = 0.8f, barWidth = 12f, barHeight = 2f,
        )
        assertTrue(placed.placed)
        assertEquals(original, placed.unplaced())
        assertFalse(placed.unplaced().placed)
        assertTrue(original.copy(starWidth = 0.99f).placed)
        assertTrue(original.copy(starHeight = 0.99f).placed)
        assertTrue(original.copy(barWidth = 0.25f).placed)
        assertTrue(original.copy(barHeight = 0.25f).placed)
    }
}
