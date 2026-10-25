package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class FxTest {
    private val fs = EqResponse.SAMPLE_RATE

    // ---- Helpers ----

    /** Stereo buffer of [seconds], each ear from its own function of time. */
    private fun stereo(seconds: Double, left: (Double) -> Double, right: (Double) -> Double = left): FloatArray {
        val n = (seconds * fs).toInt()
        return FloatArray(n * 2) { i -> val t = (i / 2) / fs; (if (i % 2 == 0) left(t) else right(t)).toFloat() }
    }

    private fun sine(f: Double, amp: Double): (Double) -> Double = { t -> amp * sin(2 * PI * f * t) }

    /** Runs [buf] through [chain] in 10 ms chunks, as the engine does. */
    private fun run(chain: FxChain, buf: FloatArray): FloatArray {
        val out = buf.copyOf()
        val chunk = 480
        var f = 0
        val frames = out.size / 2
        while (f < frames) {
            val n = minOf(chunk, frames - f)
            val part = out.copyOfRange(2 * f, 2 * (f + n))
            chain.process(part, n)
            System.arraycopy(part, 0, out, 2 * f, part.size)
            f += n
        }
        return out
    }

    private fun chainWith(p: FxChain.Params) = FxChain(fs).also { it.set(p) }

    /** One ear ([ch]) from [fromSec] on, as doubles. */
    private fun ear(buf: FloatArray, ch: Int, fromSec: Double = 0.5): DoubleArray {
        val start = (fromSec * fs).toInt()
        val frames = buf.size / 2
        return DoubleArray(frames - start) { buf[2 * (start + it) + ch].toDouble() }
    }

    private fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
    private fun db(x: Double) = 20 * log10(x + 1e-15)

    /** Amplitude of the [f] component (Goertzel). */
    private fun amp(x: DoubleArray, f: Double): Double {
        val w = 2 * PI * f / fs
        val c = 2 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s = v + c * s1 - s2
            s2 = s1
            s1 = s
        }
        val power = s1 * s1 + s2 * s2 - c * s1 * s2
        return 2 * sqrt(max(power, 0.0)) / x.size
    }

    /** Pink-ish noise (a sum of octave-spaced random-phase tones), the same every time. */
    private fun music(seconds: Double, amp: Double): FloatArray {
        val rnd = java.util.Random(7)
        val freqs = DoubleArray(40) { 30.0 * Math.pow(2.0, it / 4.0) }.filter { it < 18000 }
        val phL = freqs.map { rnd.nextDouble() * 2 * PI }
        val phR = freqs.map { rnd.nextDouble() * 2 * PI }
        val scale = amp / sqrt(freqs.size / 2.0)
        return stereo(
            seconds,
            { t -> freqs.indices.sumOf { k -> sin(2 * PI * freqs[k] * t + phL[k]) } * scale },
            { t -> freqs.indices.sumOf { k -> sin(2 * PI * freqs[k] * t + phR[k]) } * scale },
        )
    }

    private val everything = FxChain.Params(
        speakerBass = 0.6, bass = true, bassDrive = 0.6, bassWarmth = 0.7, bassDrift = 0.5, punchDb = 7.0,
        tube = 0.7, glue = true, exciter = 0.7, airDb = 4.0, airRebuild = 0.7, stage = StageMode.Wide,
        crossfeed = 0.6, expand = true,
    )

    // ---- The chain ----

    @Test
    fun offIsBitForBitTransparent() {
        val input = music(0.3, 0.3)
        val out = run(chainWith(FxChain.Params()), input)
        assertTrue(input.contentEquals(out))
    }

    @Test
    fun everythingOnStaysFiniteAndBounded() {
        val input = music(2.0, 0.5)
        val out = run(chainWith(everything), input)
        assertTrue(out.all { it.isFinite() })
        assertTrue("peak ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } < 4f)
    }

    @Test
    fun switchingOffFadesOutAndGoesQuiet() {
        // A low tone, whose own steps from sample to sample are tiny, so any click would show.
        val chain = chainWith(everything)
        run(chain, stereo(0.5, sine(100.0, 0.3)))
        chain.set(FxChain.Params())
        val input = stereo(0.5, sine(100.0, 0.3))
        val out = run(chain, input)
        assertFalse(chain.busy)
        // After the fade, the sound is the input again.
        val tail = (0.3 * fs).toInt() * 2
        for (i in tail until input.size) assertEquals(input[i], out[i], 1e-6f)
        // And the switch itself never jumps.
        var worst = 0.0
        for (i in 2 until out.size step 2) worst = max(worst, abs(out[i] - out[i - 2]).toDouble())
        assertTrue("jump $worst", worst < 0.03)
    }

    @Test
    fun crossfeedLeavesTheMiddleExactlyAlone() {
        for (f in listOf(100.0, 1000.0, 10000.0)) {
            val input = stereo(1.0, sine(f, 0.5))
            val out = run(chainWith(FxChain.Params(crossfeed = 1.0)), input)
            assertEquals(db(0.5), db(amp(ear(out, 0), f)), 0.05)
            assertEquals(db(0.5), db(amp(ear(out, 1), f)), 0.05)
        }
    }

    @Test
    fun crossfeedSendsBassToTheOtherEar() {
        val input = stereo(1.0, sine(200.0, 0.5), { 0.0 })
        val out = run(chainWith(FxChain.Params(crossfeed = 0.5)), input)
        val other = db(amp(ear(out, 1), 200.0)) - db(0.5)
        assertTrue("other ear $other dB", other > -14 && other < -4)
        // Treble barely crosses.
        val hi = run(chainWith(FxChain.Params(crossfeed = 0.5)), stereo(1.0, sine(8000.0, 0.5), { 0.0 }))
        assertTrue(db(amp(ear(hi, 1), 8000.0)) - db(0.5) < -25)
    }

    @Test
    fun stageKeepsTheMiddleInTheMiddle() {
        for (mode in listOf(StageMode.Natural, StageMode.Wide)) {
            val input = music(1.0, 0.3).let { m -> FloatArray(m.size) { m[it - it % 2] } }
            val out = run(chainWith(FxChain.Params(stage = mode)), input)
            for (i in 0 until out.size step 2) assertEquals(out[i], out[i + 1], 1e-6f)
            assertEquals(db(rms(ear(input, 0))), db(rms(ear(out, 0))), 0.05)
        }
    }

    @Test
    fun stageWidensTheSides() {
        val input = stereo(1.0, sine(6000.0, 0.3), sine(6000.0, -0.3))
        val out = run(chainWith(FxChain.Params(stage = StageMode.Wide)), input)
        assertTrue(db(amp(ear(out, 0), 6000.0)) - db(0.3) > 3.5)
    }

    @Test
    fun exciterAddsHarmonicsOnlyToTheUpperMids() {
        val chain = chainWith(FxChain.Params(exciter = 1.0))
        val out = run(chain, stereo(1.0, sine(3000.0, 0.3)))
        val x = ear(out, 0)
        val h2 = db(amp(x, 6000.0)) - db(0.3)
        val h3 = db(amp(x, 9000.0)) - db(0.3)
        assertTrue("2nd $h2", h2 > -40 && h2 < -10)
        assertTrue("3rd $h3", h3 > -45 && h3 < -10)
        // Bass and low mids pass untouched.
        val low = run(chainWith(FxChain.Params(exciter = 1.0)), stereo(1.0, sine(300.0, 0.3)))
        assertEquals(db(0.3), db(amp(ear(low, 0), 300.0)), 0.05)
        assertTrue(db(amp(ear(low, 0), 600.0)) < -80)
    }

    @Test
    fun speakerBassTradesTheFundamentalForHarmonics() {
        val out = run(chainWith(FxChain.Params(speakerBass = 1.0)), stereo(1.0, sine(60.0, 0.3)))
        val x = ear(out, 0)
        assertTrue("60 Hz ${db(amp(x, 60.0))}", db(amp(x, 60.0)) - db(0.3) < -6)
        assertTrue(db(amp(x, 180.0)) - db(0.3) > -20)
        assertTrue(db(amp(x, 240.0)) - db(0.3) > -26)
    }

    @Test
    fun analogBassDriveAddsLowHarmonics() {
        val p = FxChain.Params(bass = true, bassDrive = 1.0, bassWarmth = 1.0)
        val out = run(chainWith(p), stereo(1.5, sine(50.0, 0.3)))
        val h2 = db(amp(ear(out, 0, 1.0), 100.0)) - db(0.3)
        assertTrue("2nd $h2", h2 > -20 && h2 < -5)
        // Warmth 0 tips it odd.
        val odd = run(chainWith(p.copy(bassWarmth = 0.0)), stereo(1.5, sine(50.0, 0.3)))
        val x = ear(odd, 0, 1.0)
        assertTrue(amp(x, 150.0) > amp(x, 100.0))
    }

    @Test
    fun punchLiftsTheStartOfANoteNotItsSustain() {
        // A 70 Hz note every half second, 300 ms long.
        val note = { t: Double -> if (t % 0.5 < 0.3) 0.3 * sin(2 * PI * 70 * t) else 0.0 }
        val input = stereo(2.0, note)
        val out = run(chainWith(FxChain.Params(punchDb = 7.0)), input)
        fun peakIn(buf: FloatArray, from: Double, to: Double): Double {
            var p = 0.0
            for (f in (from * fs).toInt() until (to * fs).toInt()) p = max(p, abs(buf[2 * f].toDouble()))
            return p
        }
        val attack = db(peakIn(out, 1.0, 1.04)) - db(peakIn(input, 1.0, 1.04))
        val sustain = db(peakIn(out, 1.2, 1.29)) - db(peakIn(input, 1.2, 1.29))
        assertTrue("attack $attack", attack > 2.0)
        assertTrue("sustain $sustain", sustain < 1.0)
    }

    @Test
    fun tubeColoursGentlyAndKeepsItsLevel() {
        val input = stereo(6.0, sine(1000.0, 0.4))
        val out = run(chainWith(FxChain.Params(tube = 0.5, glue = true)), input)
        val x = ear(out, 0, 5.0)
        val h2 = db(amp(x, 2000.0)) - db(amp(x, 1000.0))
        assertTrue("2nd $h2", h2 > -45 && h2 < -25)
        // The glue's make-up brings the level back.
        assertEquals(db(0.4), db(amp(x, 1000.0)), 1.0)
    }

    @Test
    fun airLiftsMusicButNotHissAlone() {
        val hiss = music(2.0, 0.0003)
        val outHiss = run(chainWith(FxChain.Params(airDb = 5.0)), hiss)
        assertEquals(db(rms(ear(hiss, 0, 1.0))), db(rms(ear(outHiss, 0, 1.0))), 0.4)
        val loud = stereo(2.0, { t -> 0.3 * sin(2 * PI * 300 * t) + 0.05 * sin(2 * PI * 9000 * t) })
        val outLoud = run(chainWith(FxChain.Params(airDb = 5.0)), loud)
        assertTrue(db(amp(ear(outLoud, 0, 1.0), 9000.0)) - db(0.05) > 3.5)
    }

    @Test
    fun airRebuildsAMissingTopOctaveOnly() {
        val cut = stereo(2.0, { t -> 0.2 * sin(2 * PI * 9000 * t) })
        val out = run(chainWith(FxChain.Params(airRebuild = 1.0)), cut)
        val made = db(amp(ear(out, 0, 1.0), 18000.0)) - db(0.2)
        assertTrue("rebuilt $made", made > -40 && made < -12)
        // With a top octave already there, nothing's added.
        val full = stereo(2.0, { t -> 0.2 * sin(2 * PI * 9000 * t) + 0.1 * sin(2 * PI * 17000 * t) })
        val out2 = run(chainWith(FxChain.Params(airRebuild = 1.0)), full)
        assertTrue(db(amp(ear(out2, 0, 1.0), 18000.0)) - db(0.2) < -60)
    }

    @Test
    fun expanderLowersOnlyQuietPassages() {
        val quiet = run(chainWith(FxChain.Params(expand = true)), stereo(1.0, sine(1000.0, 0.002)))
        assertTrue(db(amp(ear(quiet, 0), 1000.0)) - db(0.002) < -1.5)
        val loud = run(chainWith(FxChain.Params(expand = true)), stereo(1.0, sine(1000.0, 0.3)))
        assertEquals(db(0.3), db(amp(ear(loud, 0), 1000.0)), 0.05)
    }

    @Test
    fun noEffectMakesMusicQuieter() {
        val input = music(3.0, 0.15)
        val inLevel = db(rms(ear(input, 0, 1.0)) + rms(ear(input, 1, 1.0)))
        val each = listOf(
            FxChain.Params(bass = true, bassDrive = 0.4, bassWarmth = 0.7, bassDrift = 0.2),
            FxChain.Params(punchDb = 4.0),
            FxChain.Params(tube = 0.5, glue = true),
            FxChain.Params(exciter = 0.5),
            FxChain.Params(airDb = 2.75, airRebuild = 0.5),
            FxChain.Params(stage = StageMode.Natural),
            FxChain.Params(stage = StageMode.Wide),
        )
        for (p in each) {
            val out = run(chainWith(p), input)
            val level = db(rms(ear(out, 0, 1.0)) + rms(ear(out, 1, 1.0)))
            assertTrue("$p: ${level - inLevel}", level - inLevel > -0.3)
        }
    }

    // ---- Settings into the chain ----

    @Test
    fun effectsOnlyRunWhereTheyBelong() {
        val fx = FxSettings(speakerBassOn = true, crossfeedOn = true)
        val speaker = FxChain.Params.of(fx, FxContext(OutputKind.Speaker, "speaker"))
        assertTrue(speaker.speakerBass > 0)
        assertEquals(0.0, speaker.crossfeed, 0.0)
        val wired = FxChain.Params.of(fx, FxContext(OutputKind.Wired, "wired"))
        assertEquals(0.0, wired.speakerBass, 0.0)
        assertTrue(wired.crossfeed > 0)
        // A Bluetooth speaker said to be a speaker gets no crossfeed.
        val bt = FxChain.Params.of(fx.copy(listeners = mapOf("bt:Boom" to Listener.Speaker)), FxContext(OutputKind.Bluetooth, "bt:Boom"))
        assertEquals(0.0, bt.crossfeed, 0.0)
        assertFalse(FxChain.Params.of(fx.copy(enabled = false), FxContext(OutputKind.Speaker)).anyOn)
    }

    @Test
    fun pureDropsTheGlue() {
        val fx = FxSettings(tubeOn = true, dynamics = DynamicsMode.Pure)
        assertFalse(FxChain.Params.of(fx, FxContext()).glue)
        assertTrue(FxChain.Params.of(fx.copy(dynamics = DynamicsMode.Off), FxContext()).glue)
    }

    // ---- Loudness ----

    @Test
    fun isoContourMatchesTheStandard() {
        // 1 kHz is the reference: its level in dB is its loudness in phon.
        assertEquals(40.0, Loudness.splAt(17, 40.0), 0.05)
        assertEquals(80.0, Loudness.splAt(17, 80.0), 0.1)
        // Bass needs far more level to sound as loud, quietly most of all.
        assertTrue(Loudness.splAt(7, 40.0) > 58 && Loudness.splAt(7, 40.0) < 66)
        assertTrue(Loudness.shapeDb(50.0, 40.0) > Loudness.shapeDb(50.0, 80.0) + 5)
    }

    @Test
    fun loudnessCompensatesAtLowVolumeOnly() {
        assertEquals(0.0, Loudness.compensationDb(1000.0, 50.0, 80.0), 1e-9)
        assertTrue(Loudness.compensationDb(50.0, 50.0, 80.0) > 8)
        assertTrue(Loudness.bands(80.0, 80.0, 1.0).isEmpty())
        val bands = Loudness.bands(50.0, 80.0, 1.0)
        val profile = EqProfile(name = "", bands = bands)
        for (f in listOf(40.0, 80.0, 160.0, 400.0, 1000.0, 3000.0, 8000.0)) {
            val want = Loudness.compensationDb(f, 50.0, 80.0)
            assertEquals("at $f", want, EqResponse.gainAt(profile, f), 1.5)
        }
    }

    @Test
    fun volumeSetsThePhon() {
        assertEquals(80.0, Loudness.phonAt(80.0, 0.0), 0.0)
        assertEquals(50.0, Loudness.phonAt(80.0, -30.0), 0.0)
        assertEquals(Loudness.MIN_PHON, Loudness.phonAt(80.0, -90.0), 0.0)
    }

    // ---- Tone ----

    @Test
    fun toneIsEmptyWhenOff() {
        assertTrue(FxTone.bands(FxSettings(), FxContext()).isEmpty())
        assertTrue(FxTone.bands(FxSettings(enabled = false, tubeOn = true, bassOn = true), FxContext()).isEmpty())
    }

    @Test
    fun stageSmileLeavesTheMidsAlone() {
        val tone = FxTone.bands(FxSettings(stage = StageMode.Wide), FxContext(OutputKind.Wired))
        val p = EqProfile(name = "", bands = tone)
        for (f in listOf(250.0, 500.0, 1000.0, 2000.0)) assertTrue("at $f: ${EqResponse.gainAt(p, f)}", abs(EqResponse.gainAt(p, f)) < 0.6)
        assertTrue(EqResponse.gainAt(p, 30.0) > 2.0)
        assertTrue(EqResponse.gainAt(p, 16000.0) > 2.0)
    }

    @Test
    fun pultecLiftsAndCuts() {
        val tone = FxTone.bands(FxSettings(bassOn = true, pultecBoost = 1.0, pultecCut = 1.0, pultecFreq = 2), FxContext(OutputKind.Wired))
        val p = EqProfile(name = "", bands = tone)
        assertTrue(EqResponse.gainAt(p, 40.0) > 5.0)
        assertTrue(EqResponse.gainAt(p, 180.0) < EqResponse.gainAt(p, 60.0) - 4.0)
    }

    @Test
    fun bluetoothCareOnlyOnBluetooth() {
        val fx = FxSettings(bluetoothCare = true)
        assertTrue(FxTone.bands(fx, FxContext(OutputKind.Wired)).isEmpty())
        val p = EqProfile(name = "", bands = FxTone.bands(fx, FxContext(OutputKind.Bluetooth)))
        assertTrue(EqResponse.gainAt(p, 19000.0) < -3)
    }

    // ---- Direct mode ----

    @Test
    fun directIsNeutralWithNothingOn() {
        val plan = FxDirect.plan(FxSettings(), FxContext())
        assertTrue(plan.mbc.all { it.neutral })
        assertTrue(plan.tone.isEmpty())
        assertNull(plan.widthDb)
        assertEquals(LimiterSpec(), plan.limiter)
        assertEquals(FxDirect.CUTOFFS.size, plan.mbc.size)
    }

    @Test
    fun directPunchCompressesTheBassBandsWithASlowAttack() {
        val plan = FxDirect.plan(FxSettings(punch = PunchMode.High), FxContext(OutputKind.Wired))
        for (i in 0..2) {
            assertTrue(plan.mbc[i].ratio > 1.0)
            assertTrue(plan.mbc[i].attackMs >= 20.0)
            assertTrue(plan.mbc[i].postGainDb > 0.0)
        }
        assertTrue(plan.mbc.drop(3).all { it.neutral })
    }

    @Test
    fun directWidthNeverLeansToOneSide() {
        val w = FxDirect.width(StageMode.Wide)
        assertNotNull(w)
        // Lifts and cuts alternate, so neither ear gains overall across the treble.
        var sum = 0.0
        var any = 0
        for (i in 0 until BandPlan.BANDS) if (w!![i] != 0f) {
            sum += w[i]
            any++
        }
        assertTrue(any > 10)
        assertTrue("sum $sum over $any", abs(sum / any) < 1.0)
        assertTrue(w!!.withIndex().all { (i, v) -> v == 0f || BandPlan.centre(i) >= 3150.0 })
        assertNull(FxDirect.width(StageMode.Front))
    }

    @Test
    fun directPureSoftensTheLimiter() {
        assertTrue(FxDirect.plan(FxSettings(dynamics = DynamicsMode.Pure), FxContext()).limiter.ratio < LimiterSpec().ratio)
    }

    @Test
    fun softLimiterStillHoldsTheCeiling() {
        val lim = Limiter(fs)
        lim.soft = true
        val buf = stereo(0.5, sine(1000.0, 1.4))
        lim.process(buf, buf.size / 2)
        assertTrue(buf.maxOf { abs(it) } <= 1f)
        // Comfortably below the shoulder: untouched.
        val fresh = Limiter(fs).also { it.soft = true }
        val quiet = stereo(0.2, sine(1000.0, 0.5))
        val copy = quiet.copyOf()
        fresh.process(quiet, quiet.size / 2)
        for (i in quiet.indices) assertEquals(copy[i], quiet[i], 1e-6f)
        // Between the shoulder and the ceiling, peaks are bent rather than passed straight.
        val near = stereo(0.2, sine(1000.0, 0.9))
        Limiter(fs).also { it.soft = true }.process(near, near.size / 2)
        assertTrue(near.maxOf { abs(it) } < 0.89f)
    }
}
