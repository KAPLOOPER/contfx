package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** An EQ that trims left and right apart plays each side through its own filters and level. */
class SidesTest {
    private val fs = EqResponse.SAMPLE_RATE

    private fun level(eq: ParametricEq, hz: Double): Pair<Double, Double> {
        var l = 0.0
        var r = 0.0
        repeat(40) { c ->
            val buf = FloatArray(480 * 2) { i -> (0.1 * sin(2 * PI * hz * (c * 480 + i / 2) / fs)).toFloat() }
            eq.process(buf, 480)
            if (c >= 30) for (i in buf.indices step 2) { l += buf[i] * buf[i]; r += buf[i + 1] * buf[i + 1] }
        }
        val ref = 0.1 * 0.1 / 2 * 480 * 10
        return 10 * log10(l / ref) to 10 * log10(r / ref)
    }

    @Test
    fun eachSideHearsItsOwnTrim() {
        val shared = listOf(EqBand(FilterType.PEAK, 1000.0, -3.0, 1.0))
        val p = EqProfile(
            name = "Pair",
            bands = shared,
            left = SideTrim(2.0, listOf(EqBand(FilterType.PEAK, 3000.0, 4.0, 1.5))),
            right = SideTrim(-1.0),
        )
        assertTrue(p.sided)
        val eq = ParametricEq(2, fs)
        eq.setProfile(p)
        val (l1k, r1k) = level(eq, 1000.0)
        assertEquals(-3.0 + 2.0 + EqResponse.gainAt(EqProfile(name = "", bands = p.left!!.bands), 1000.0), l1k, 0.15)
        assertEquals(-3.0 - 1.0, r1k, 0.15)
        val eq3 = ParametricEq(2, fs)
        eq3.setProfile(p)
        val (l3k, r3k) = level(eq3, 3000.0)
        assertTrue("left lifted at 3 kHz: $l3k vs $r3k", l3k - r3k > 6.0)
        // The suggested preamp covers the louder side.
        val peakLeft = EqResponse.peakDb(p.side(p.left)) + 2.0
        assertEquals(-kotlin.math.ceil(peakLeft * 10) / 10, EqResponse.suggestedPreamp(p), 1e-9)
    }

    @Test
    fun withoutTrimsBothSidesAreTheSame() {
        val p = EqProfile(name = "", bands = listOf(EqBand(FilterType.LOW_SHELF, 100.0, 4.0, 0.7)), left = SideTrim(), right = null)
        assertFalse(p.sided)
        val eq = ParametricEq(2, fs)
        eq.setProfile(p)
        val (l, r) = level(eq, 60.0)
        assertEquals(l, r, 1e-6)
        assertTrue(sqrt(l * l) < 5.0)
    }
}
