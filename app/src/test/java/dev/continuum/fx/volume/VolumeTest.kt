package dev.continuum.fx.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelTest {
    @Test
    fun fractionFollowsTheDevicesOwnSteps() {
        val media = Level(index = 11, min = 0, max = 15)
        assertEquals(73, media.percent)
        assertEquals(15, media.steps)
        assertFalse(media.atMax)
        assertTrue(Level(15, 0, 15).atMax)
    }

    @Test
    fun aCallStreamStartsAtOne() {
        val call = Level(index = 1, min = 1, max = 7)
        assertEquals(0f, call.fraction, 1e-6f)
        assertEquals(1, call.indexAt(0f))
        assertEquals(7, call.indexAt(1f))
    }

    @Test
    fun aTouchLandsOnTheNearestStep() {
        val media = Level(index = 0, min = 0, max = 15)
        assertEquals(8, media.indexAt(0.5f))
        assertEquals(0, media.indexAt(-0.2f))
        assertEquals(15, media.indexAt(1.4f))
    }
}

class BoostPolicyTest {
    @Test
    fun eachFivePointsIsAnotherDecibelAndAHalf() {
        assertEquals(120, BoostPolicy.percent(4))
        assertEquals(6f, BoostPolicy.db(4), 1e-6f)
        assertEquals(600, BoostPolicy.millibels(4))
        assertEquals(150, BoostPolicy.percent(10))
        assertEquals(15f, BoostPolicy.db(10), 1e-6f)
    }

    @Test
    fun theCeilingStaysInRange() {
        assertEquals(BoostPolicy.MIN_MAX_STEPS, BoostPolicy.normalizeMaxSteps(0))
        assertEquals(BoostPolicy.HARD_MAX_STEPS, BoostPolicy.normalizeMaxSteps(99))
        assertEquals(100, BoostPolicy.percent(-3))
    }
}
