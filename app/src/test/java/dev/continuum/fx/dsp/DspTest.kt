package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {

    private val fs = EqResponse.SAMPLE_RATE

    @Test
    fun peakFilterHitsItsGainAtCentre() {
        val band = EqBand(FilterType.PEAK, 1000.0, 6.0, 1.0)
        val db = BiquadCoefficients.design(band, fs).magnitudeDb(1000.0, fs)
        assertEquals(6.0, db, 0.01)
    }

    @Test
    fun shelvesReachFullGainFarFromCorner() {
        val low = BiquadCoefficients.design(EqBand(FilterType.LOW_SHELF, 200.0, 8.0, 0.707), fs)
        assertEquals(8.0, low.magnitudeDb(20.0, fs), 0.3)
        assertEquals(0.0, low.magnitudeDb(10000.0, fs), 0.1)
        val high = BiquadCoefficients.design(EqBand(FilterType.HIGH_SHELF, 4000.0, -5.0, 0.707), fs)
        assertEquals(-5.0, high.magnitudeDb(18000.0, fs), 0.3)
        assertEquals(0.0, high.magnitudeDb(50.0, fs), 0.1)
    }

    @Test
    fun allPassIsFlat() {
        val ap = BiquadCoefficients.design(EqBand(FilterType.ALL_PASS, 1000.0, 0.0, 0.7), fs)
        for (f in listOf(50.0, 1000.0, 15000.0)) assertEquals(0.0, ap.magnitudeDb(f, fs), 1e-6)
    }

    @Test
    fun processedSineMatchesPredictedResponse() {
        val profile = EqProfile(name = "t", preampDb = -2.0, bands = listOf(EqBand(FilterType.PEAK, 1000.0, 6.0, 1.0)))
        val eq = ParametricEq(channels = 2, sampleRate = fs)
        eq.setProfile(profile)
        val frames = 48000
        val buf = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = (0.1 * sin(2 * PI * 1000.0 * i / fs)).toFloat()
            buf[2 * i] = v
            buf[2 * i + 1] = v
        }
        eq.process(buf, frames)
        var sum = 0.0
        val start = frames / 2
        for (i in start until frames) sum += buf[2 * i] * buf[2 * i].toDouble()
        val rms = sqrt(sum / (frames - start))
        val gainDb = 20 * log10(rms / (0.1 / sqrt(2.0)))
        assertEquals(4.0, gainDb, 0.05)
    }

    @Test
    fun limiterKeepsPeaksUnderCeiling() {
        val limiter = Limiter(fs, ceilingDb = -1.0)
        val frames = 4800
        val buf = FloatArray(frames * 2) { i -> (1.8 * sin(2 * PI * 200.0 * (i / 2) / fs)).toFloat() }
        limiter.process(buf, frames)
        var peak = 0.0
        for (v in buf) peak = max(peak, abs(v.toDouble()))
        assertTrue(peak <= 10.0.pow(-1.0 / 20.0) + 1e-6)
    }

    @Test
    fun suggestedPreampCancelsTheLargestBoost() {
        val profile = EqProfile(name = "t", bands = listOf(EqBand(FilterType.PEAK, 100.0, 7.5, 0.8)))
        val pre = EqResponse.suggestedPreamp(profile)
        assertEquals(-7.5, pre, 0.15)
    }

    private fun Double.pow(e: Double): Double = Math.pow(this, e)
}

class EqChangeTest {
    private val fs = EqResponse.SAMPLE_RATE

    /** Switching profiles mid-song should never jump: the old and new filters cross-fade. */
    @Test
    fun changingSettingsDoesNotClick() {
        val eq = ParametricEq(channels = 2, sampleRate = fs)
        val a = EqProfile(name = "a", bands = listOf(EqBand(FilterType.LOW_SHELF, 120.0, 9.0, 0.7)))
        val b = EqProfile(name = "b", preampDb = -6.0, bands = listOf(EqBand(FilterType.PEAK, 80.0, -9.0, 2.0), EqBand(FilterType.HIGH_SHELF, 6000.0, 6.0, 0.7)))
        eq.setProfile(a)
        val chunk = 480
        var phase = 0
        var last = 0f
        var worst = 0f
        for (n in 0 until 200) {
            if (n == 100) eq.setProfile(b)
            if (n == 150) eq.setProfile(null)
            val buf = FloatArray(chunk * 2)
            for (i in 0 until chunk) {
                val v = (0.25 * sin(2 * PI * 60.0 * phase / fs)).toFloat()
                buf[2 * i] = v
                buf[2 * i + 1] = v
                phase++
            }
            eq.process(buf, chunk)
            for (i in 0 until chunk) {
                if (n > 2) worst = max(worst, abs(buf[2 * i] - last))
                last = buf[2 * i]
            }
        }
        // A 60 Hz sine at these levels moves at most ~0.01 per sample; a click would be far bigger.
        assertTrue("largest step $worst", worst < 0.02f)
    }
}

class SpectrumTest {
    private val fs = EqResponse.SAMPLE_RATE

    @Test
    fun aToneLightsUpItsOwnBand() {
        val tap = SpectrumTap(8192)
        val analyzer = SpectrumAnalyzer(4096, 56, fs)
        val out = FloatArray(56)
        val samples = FloatArray(4096)
        for (tone in listOf(60.0, 100.0, 1000.0, 8000.0)) {
            val buf = FloatArray(8192 * 2)
            for (i in 0 until 8192) {
                val v = (0.5 * sin(2 * PI * tone * i / fs)).toFloat()
                buf[2 * i] = v; buf[2 * i + 1] = v
            }
            tap.push(buf, 8192, 2)
            tap.latest(samples)
            // Settle on the tone for a while, as the screen would.
            repeat(30) { analyzer.analyze(samples, out, 1f / 30f) }
            val loudest = out.indices.maxBy { out[it] }
            val centre = SpectrumAnalyzer.centre(loudest, 56)
            assertTrue("tone $tone peaked at band $loudest ($centre Hz)", centre / tone in 0.8..1.25)
            assertTrue(out[loudest] > 0.6f)
        }
    }

    @Test
    fun silenceSinksToRest() {
        val analyzer = SpectrumAnalyzer(4096, 56, fs)
        val out = FloatArray(56)
        val loud = FloatArray(4096) { (0.5 * sin(2 * PI * 440.0 * it / fs)).toFloat() }
        repeat(10) { analyzer.analyze(loud, out, 1f / 30f) }
        repeat(90) { analyzer.analyze(FloatArray(4096), out, 1f / 30f) }
        assertTrue(out.max() < 0.05f)
    }
}
