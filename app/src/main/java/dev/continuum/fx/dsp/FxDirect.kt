package dev.continuum.fx.dsp

import kotlin.math.max

/** One band of Android's multiband compressor, as plain numbers. Defaults leave the sound alone. */
data class MbcSpec(
    val cutoff: Double,
    val attackMs: Double = 3.0,
    val releaseMs: Double = 80.0,
    val ratio: Double = 1.0,
    val thresholdDb: Double = -45.0,
    val kneeDb: Double = 0.0,
    val gateDb: Double = -90.0,
    val expanderRatio: Double = 1.0,
    val preGainDb: Double = 0.0,
    val postGainDb: Double = 0.0,
) {
    val neutral: Boolean get() = ratio == 1.0 && expanderRatio == 1.0 && preGainDb == 0.0 && postGainDb == 0.0
}

/** The effect's final limiter. */
data class LimiterSpec(val attackMs: Double = 1.0, val releaseMs: Double = 60.0, val ratio: Double = 10.0, val thresholdDb: Double = -1.0)

/** Everything Direct mode's effect needs from the effects: tone, each ear's extra, the compressor bands and the limiter. */
data class DirectFx(
    val tone: List<EqBand>,
    /** Per [BandPlan] band, added to the left ear (and taken from the right) for width. Null when none. */
    val widthDb: FloatArray?,
    val mbc: List<MbcSpec>,
    val limiter: LimiterSpec,
) {
    override fun equals(other: Any?): Boolean = other is DirectFx && tone == other.tone && mbc == other.mbc &&
        limiter == other.limiter && (widthDb?.contentEquals(other.widthDb) ?: (other.widthDb == null))

    override fun hashCode(): Int = tone.hashCode() * 31 + mbc.hashCode()
}

/**
 * The effects for Direct mode, where they run inside Android's own DynamicsProcessing effect on
 * each app's sound. That effect has a band EQ (the EQ and the effects' tone share its 96 bands),
 * a seven-band compressor, and a limiter, and nothing else: it can't make harmonics or mix one
 * ear into the other. So what moves with the music is done with the compressor bands, closely
 * enough to recognise, and crossfeed waits for Capture mode.
 */
object FxDirect {
    /** Where the compressor's bands end, chosen so each effect has the region it works in. */
    val CUTOFFS = doubleArrayOf(60.0, 120.0, 300.0, 2000.0, 5200.0, 10000.0, 20000.0)

    private class Band(val cutoff: Double) {
        var comp: MbcSpec? = null
        var gateDb = -90.0
        var expander = 1.0
        var post = 0.0

        /** The strongest compression asked for wins; make-up gains add. */
        fun compress(ratio: Double, threshold: Double, knee: Double, attack: Double, release: Double) {
            val c = comp
            if (c == null || ratio > c.ratio) comp = MbcSpec(cutoff, attack, release, ratio, threshold, knee)
        }

        fun expand(gate: Double, ratio: Double) {
            gateDb = max(gateDb, gate)
            expander = max(expander, ratio)
        }

        fun spec(): MbcSpec {
            val c = comp ?: MbcSpec(cutoff)
            return c.copy(gateDb = gateDb, expanderRatio = expander, postGainDb = round(post))
        }
    }

    fun plan(fx: FxSettings, ctx: FxContext): DirectFx {
        val dctx = ctx.copy(direct = true)
        val bands = CUTOFFS.map { Band(it) }
        if (!fx.enabled) return DirectFx(emptyList(), null, bands.map { it.spec() }, LimiterSpec())
        val l = dctx.listener(fx)

        if (fx.tubeOn && fx.dynamics != DynamicsMode.Pure) {
            val t = fx.tube.coerceIn(0.0, 1.0)
            for (b in bands) {
                b.compress(1.0 + 0.5 * t, -24.0, 10.0, 30.0, 300.0)
                b.post += 1.0 * t
            }
        }
        if (fx.bassOn) {
            val d = fx.bassDrive.coerceIn(0.0, 1.0)
            val w = fx.bassWarmth.coerceIn(0.0, 1.0)
            if (d > 0) {
                bands[0].compress(1.8 + 3.2 * d, -30.0, 6.0, 10.0, 120.0)
                bands[0].post += 3.0 * d * l.bass
            }
            if (w > 0) {
                bands[1].compress(1.2 + 0.6 * w, -28.0, 8.0, 15.0, 150.0)
                bands[1].post += 2.5 * w * l.bass
            }
        }
        if (fx.punch != PunchMode.Off) {
            // A slow attack lets each note's start through before holding the rest down: punch.
            val high = fx.punch == PunchMode.High
            for (i in 0..2) {
                bands[i].compress(if (high) 4.0 else 2.5, -28.0, 6.0, 25.0, 150.0)
                bands[i].post += (if (high) 5.0 else 3.0) * l.bass
            }
        }
        if (fx.speakerBassOn && dctx.onPhoneSpeaker) {
            // Holds the speaker back from bass it can't play, which would only make it strain.
            for (i in 0..1) bands[i].compress(10.0, -36.0, 0.0, 1.0, 50.0)
        }
        if (fx.exciterOn) {
            val e = fx.exciter.coerceIn(0.0, 1.0)
            bands[4].compress(2.0, -32.0, 8.0, 1.0, 40.0)
            bands[4].post += 4.0 * e
            bands[5].compress(2.0, -32.0, 8.0, 1.0, 40.0)
            bands[5].post += 2.5 * e
        }
        if (fx.airOn) {
            val a = fx.air.coerceIn(0.0, 1.0)
            // Lifted while there's music up there; hiss alone is let down instead.
            bands[5].expand(-60.0, 2.0)
            bands[5].post += 3.0 * a * l.treble
            bands[6].expand(-60.0, 2.0)
            bands[6].post += 5.5 * a * l.treble
        }
        if (fx.dynamics == DynamicsMode.Restore) {
            for (b in bands) b.expand(-40.0, 1.12)
        }

        val limiter = if (fx.dynamics == DynamicsMode.Pure) LimiterSpec(1.0, 80.0, 4.0, -1.5) else LimiterSpec()
        return DirectFx(FxTone.bands(fx, dctx), width(fx.stage), bands.map { it.spec() }, limiter)
    }

    /**
     * Width without mixing the ears: in alternate stretches of the treble, one ear is lifted a
     * little and the other lowered by the same, swapping each time. Every stretch sums to nothing,
     * so nothing leans to one side, but the ears differ more, and the sound spreads.
     */
    fun width(mode: StageMode): FloatArray? {
        val (from, db) = when (mode) {
            StageMode.Natural -> 6300.0 to 1.5
            StageMode.Wide -> 3150.0 to 2.5
            else -> return null
        }
        val edges = doubleArrayOf(3150.0, 5000.0, 8000.0, 12500.0, 20001.0)
        val out = FloatArray(BandPlan.BANDS)
        for (i in 0 until BandPlan.BANDS) {
            val f = BandPlan.centre(i)
            if (f < from) continue
            var k = 0
            while (k < edges.size - 1 && f >= edges[k + 1]) k++
            val sign = if (k % 2 == 0) 1.0 else -1.0
            val lift = if (mode == StageMode.Wide) db + 1.0 * (k / 3.0) else db
            out[i] = (sign * lift).toFloat()
        }
        return out
    }

    private fun round(x: Double) = kotlin.math.round(x * 100.0) / 100.0
}
