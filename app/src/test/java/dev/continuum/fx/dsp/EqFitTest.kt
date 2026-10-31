package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

class EqFitTest {

    private fun curve(name: String, fn: (Double) -> Double): Curve {
        val f = DoubleArray(480) { 20.0 * 1000.0.pow(it / 479.0) }
        return Curve(name, f, DoubleArray(f.size) { fn(f[it]) })
    }

    @Test
    fun readsTheUsualFileShapes() {
        val tab = (0 until 50).joinToString("\n") { i -> "${20.0 * 1.15.pow(i)}\t${i * 0.1}" }
        assertEquals(50, Curve.parse(tab, "a.txt").getOrThrow().size)

        val rew = "* Measurement data\n* Freq(Hz) SPL(dB) Phase(degrees)\n" +
            (0 until 50).joinToString("\n") { i -> "%.3f %.3f 0.0".format(java.util.Locale.US, 20.0 * 1.15.pow(i), 80.0 + i * 0.1) }
        val r = Curve.parse(rew, "rew.txt").getOrThrow()
        assertEquals(50, r.size)
        assertEquals(80.0, r.db[0], 1e-6)

        val csv = "frequency,raw\n" + (0 until 50).joinToString("\n") { i -> "${20.0 * 1.15.pow(i)},${-i * 0.2}" }
        assertEquals(50, Curve.parse(csv, "x.csv").getOrThrow().size)

        val european = (0 until 50).joinToString("\n") { i -> "${"%.2f".format(java.util.Locale.GERMANY, 20.0 * 1.15.pow(i))};${"%.2f".format(java.util.Locale.GERMANY, i * 0.5)}" }
        val e = Curve.parse(european, "de.txt").getOrThrow()
        assertEquals(50, e.size)
        assertEquals(24.5, e.db.last(), 0.01)

        assertTrue(Curve.parse("hello\nworld", "no.txt").isFailure)
    }

    @Test
    fun theFittedEqTurnsTheMeasurementIntoTheTarget() {
        // A target with a bass shelf and the ear's 3 kHz gain; earphones with their own bumps.
        val target = curve("Target") { f ->
            6.0 / (1 + (f / 105.0).pow(2)) + 9.0 * Math.exp(-(log10(f / 3000.0) / 0.22).pow(2))
        }
        val rnd = Random(3)
        val flaws = EqProfile(
            name = "flaws",
            bands = listOf(
                EqBand(FilterType.PEAK, 200.0, 3.5, 1.2),
                EqBand(FilterType.PEAK, 1500.0, -4.0, 2.0),
                EqBand(FilterType.PEAK, 6000.0, 5.0, 3.0),
                EqBand(FilterType.LOW_SHELF, 80.0, -3.0, 0.7),
            ),
        )
        val measured = curve("IEM") { f -> target.at(f) + EqResponse.gainAt(flaws, f) + 75.0 + rnd.nextDouble(-0.3, 0.3) }
        val t0 = System.nanoTime()
        val result = EqFit.fit(measured, target)
        val ms = (System.nanoTime() - t0) / 1e6
        println("  off by ${"%.2f".format(result.errorBefore)} dB, with the EQ ${"%.2f".format(result.errorAfter)} dB, ${result.profile.bands.size} filters, ${"%.0f".format(ms)} ms")
        result.profile.bands.forEach { println("    ${it.type.code} ${it.frequency.toInt()} Hz ${it.gainDb} dB Q ${it.q}") }
        assertTrue(result.errorBefore > 2.0)
        assertTrue("error ${result.errorAfter}", result.errorAfter < 0.6)
        assertTrue("preamp keeps headroom", result.profile.preampDb <= 0.0)
        // Nothing lifted past the limit.
        assertTrue(result.response.maxOrNull()!! <= 6.5)
    }

    @Test
    fun boostsStayWithinTheLimit() {
        val target = curve("Target") { 0.0 }
        val measured = curve("Deep notch") { f -> -14.0 * Math.exp(-(log10(f / 4000.0) / 0.05).pow(2)) }
        val result = EqFit.fit(measured, target, EqFit.Options(maxBoostDb = 3.0))
        assertTrue("max ${result.response.maxOrNull()}", result.response.maxOrNull()!! <= 3.5)
    }
}
