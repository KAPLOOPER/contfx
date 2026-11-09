package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Reproducible limits for selected synthetic inputs, not a claim of alias-free processing. */
class SpectralRegressionTest {
    private fun amplitude(samples: DoubleArray, frequency: Double, sr: Double): Double {
        val c = 2 * cos(2 * PI * frequency / sr)
        var a = 0.0
        var b = 0.0
        for (x in samples) {
            val y = x + c * a - b
            b = a
            a = y
        }
        return 2 * sqrt(max(0.0, a * a + b * b - c * a * b)) / samples.size
    }

    private fun db(value: Double) = 20 * log10(max(value, 1e-12))

    private fun render(params: FxChain.Params, sr: Double, tone: (Double) -> Double): Pair<FloatArray, FloatArray> {
        val frames = sr.toInt() * 2
        val input = FloatArray(frames * 2) { tone((it / 2) / sr).toFloat() }
        val output = input.copyOf()
        val chain = FxChain(sr).also { it.set(params) }
        var position = 0
        while (position < frames) {
            val count = minOf(480, frames - position)
            val chunk = output.copyOfRange(position * 2, (position + count) * 2)
            chain.process(chunk, count)
            chunk.copyInto(output, position * 2)
            position += count
        }
        return input to output
    }

    @Test
    fun multitoneWaveshapingProducesIntermodulationNotJustHarmonics() {
        val sr = 48000.0
        val gen = Harmonics(1.0, 100.0, sr)
        val output = DoubleArray(sr.toInt() * 2) { i ->
            val t = i / sr
            gen.run(0.2 * sin(2 * PI * 2000 * t) + 0.2 * sin(2 * PI * 3000 * t), 0.4, 0.0)
        }.copyOfRange(sr.toInt(), sr.toInt() * 2)
        // The sum and difference aren't harmonics of either source tone.
        assertTrue(db(amplitude(output, 1000.0, sr)) > -40.0)
        assertTrue(db(amplitude(output, 5000.0, sr)) > -40.0)
    }

    @Test
    fun waveshapingAboveItsIntendedSourceBandCanAlias() {
        val sr = 48000.0
        val gen = Harmonics(1.0, 100.0, sr)
        val output = DoubleArray(sr.toInt() * 2) { i ->
            gen.run(0.3 * sin(2 * PI * 9000 * i / sr), 0.0, 0.4)
        }.copyOfRange(sr.toInt(), sr.toInt() * 2)
        // 3 * 9 kHz is above Nyquist; at 48 kHz it folds to 21 kHz. This deliberately
        // demonstrates why the utility itself cannot promise "nothing aliases".
        assertTrue(db(amplitude(output, 21000.0, sr) / 0.3) > -30.0)
    }

    @Test
    fun filteredExciterKeepsSelectedFoldedThirdHarmonicsBelowMinus25Dbc() {
        for ((sr, frequency) in listOf(44100.0 to 8000.0, 48000.0 to 9000.0)) {
            val (input, output) = render(FxChain.Params(exciter = 1.0), sr) { t ->
                0.3 * sin(2 * PI * frequency * t)
            }
            val added = DoubleArray(sr.toInt()) { i ->
                val p = (i + sr.toInt()) * 2
                (output[p] - input[p]).toDouble()
            }
            val folded = sr - 3 * frequency
            val dbc = db(amplitude(added, folded, sr) / 0.3)
            println("exciter sr=$sr input=$frequency folded=$folded: $dbc dBc")
            assertTrue("$sr Hz: folded component $dbc dBc", dbc < -25.0)
        }
    }

    @Test
    fun transientsAtSeveralSampleRatesRemainFiniteAndLimited() {
        val all = FxChain.Params(speakerBass = 1.0, bass = true, bassDrive = 1.0,
            bassWarmth = 1.0, bassDrift = 1.0, punchDb = 7.0, tube = 1.0, glue = true,
            exciter = 1.0, airDb = 5.5, airRebuild = 1.0, stage = StageMode.Wide,
            crossfeed = 1.0, expand = true)
        for (sr in listOf(44100.0, 48000.0, 96000.0)) {
            val (_, output) = render(all, sr) { t ->
                val burst = t % 0.2
                if (burst < 0.001) 0.9 else if (burst < 0.04) {
                    0.45 * sin(2 * PI * 700 * t) + 0.45 * sin(2 * PI * 9000 * t)
                } else 0.0
            }
            Limiter(sr).process(output, output.size / 2)
            assertTrue("nonfinite at $sr", output.all { it.isFinite() })
            assertTrue("ceiling at $sr", output.all { abs(it) <= 1f })
            for (i in output.indices step 2) assertEquals(output[i], output[i + 1], 1e-6f)
        }
    }
}
