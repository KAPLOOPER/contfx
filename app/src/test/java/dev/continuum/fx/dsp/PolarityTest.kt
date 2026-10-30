package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class PolarityTest {
    private val fs = EqResponse.SAMPLE_RATE

    /** Stereo sine, left and right at different frequencies so a mix-up would show. */
    private fun tone(frames: Int, from: Int = 0) = FloatArray(frames * 2) { i ->
        val t = (from + i / 2) / fs
        (if (i % 2 == 0) 0.4 * sin(2 * PI * 220 * t) else 0.3 * sin(2 * PI * 330 * t)).toFloat()
    }

    /** Runs [chunks] 10 ms chunks through [eq], returning the input and output of the last one. */
    private fun settle(eq: ParametricEq, chunks: Int = 5): Pair<FloatArray, FloatArray> {
        var input = FloatArray(0)
        var out = FloatArray(0)
        repeat(chunks) { c ->
            input = tone(480, c * 480)
            out = input.copyOf()
            eq.process(out, 480)
        }
        return input to out
    }

    @Test
    fun invertedTurnsBothChannelsOver() {
        val eq = ParametricEq(2, fs)
        eq.setProfile(EqProfile(name = "", polarity = Polarity.Inverted))
        val (input, out) = settle(eq)
        for (i in input.indices) assertEquals(-input[i], out[i], 1e-6f)
    }

    @Test
    fun oneChannelOnlyTurnsThatChannelOver() {
        val eq = ParametricEq(2, fs)
        eq.setProfile(EqProfile(name = "", polarity = Polarity.LeftInverted))
        val (input, out) = settle(eq)
        for (i in input.indices step 2) {
            assertEquals(-input[i], out[i], 1e-6f)
            assertEquals(input[i + 1], out[i + 1], 1e-6f)
        }
    }

    @Test
    fun polarityComesAfterTheFiltersAndChangesNothingElse() {
        val bands = listOf(EqBand(FilterType.LOW_SHELF, 60.0, 6.0, 0.7), EqBand(FilterType.PEAK, 3000.0, -4.0, 1.4))
        val normal = ParametricEq(2, fs).also { it.setProfile(EqProfile(name = "", preampDb = -6.0, bands = bands)) }
        val flipped = ParametricEq(2, fs).also { it.setProfile(EqProfile(name = "", preampDb = -6.0, bands = bands, polarity = Polarity.Inverted)) }
        val a = settle(normal).second
        val b = settle(flipped).second
        for (i in a.indices) assertEquals(-a[i], b[i], 1e-6f)
    }

    @Test
    fun staysTurnedOverWithTheEqOff() {
        val eq = ParametricEq(2, fs)
        eq.setProfile(null, Polarity.Inverted)
        val (input, out) = settle(eq)
        for (i in input.indices) assertEquals(-input[i], out[i], 1e-6f)
        // And Normal with no EQ leaves the sound exactly alone.
        val plain = ParametricEq(2, fs).also { it.setProfile(null, Polarity.Normal) }
        val (in2, out2) = settle(plain)
        assertTrue(in2.contentEquals(out2))
    }

    @Test
    fun turningItOverMidSongFadesRatherThanClicks() {
        val eq = ParametricEq(2, fs)
        eq.setProfile(EqProfile(name = "", bands = listOf(EqBand(FilterType.LOW_SHELF, 40.0, 8.0, 0.7))))
        settle(eq, 20)
        eq.setProfile(EqProfile(name = "", bands = listOf(EqBand(FilterType.LOW_SHELF, 40.0, 8.0, 0.7)), polarity = Polarity.Inverted))
        val input = tone(4800, 20 * 480)
        val out = input.copyOf()
        var f = 0
        while (f < 4800) {
            val part = out.copyOfRange(2 * f, 2 * (f + 480))
            eq.process(part, 480)
            System.arraycopy(part, 0, out, 2 * f, part.size)
            f += 480
        }
        // A 220 Hz tone moves at most about 0.4 * 2π * 220 / 48000 per sample (times the shelf's
        // small lift there): nothing near a click.
        var worst = 0.0
        for (i in 2 until out.size step 2) worst = max(worst, abs(out[i] - out[i - 2]).toDouble())
        assertTrue("step $worst", worst < 0.03)
    }

    @Test
    fun equalizerApoCopyLinesRoundTrip() {
        val text = "Preamp: -3.0 dB\nCopy: L=-1*L R=-1*R\nFilter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.00\n"
        val p = EqImport.parse(text).profile!!
        assertEquals(Polarity.Inverted, p.polarity)
        assertEquals(Polarity.LeftInverted, EqImport.parse("Copy: L=-L R=R\nFilter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.00").profile!!.polarity)
        assertEquals(Polarity.Normal, EqImport.parse("Copy: L=R R=L\nFilter 1: ON PK Fc 1000 Hz Gain -2.0 dB Q 1.00").profile!!.polarity)
        for (pol in Polarity.entries) {
            val back = EqImport.parse(EqImport.export(p.copy(polarity = pol))).profile!!
            assertEquals(pol, back.polarity)
        }
        // A normal profile exports without a Copy line, as before.
        assertTrue(!EqImport.export(p.copy(polarity = Polarity.Normal)).contains("Copy"))
    }
}
