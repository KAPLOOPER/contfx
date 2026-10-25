package dev.continuum.fx.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.HandlerThread
import dev.continuum.fx.data.Library
import dev.continuum.fx.dsp.BandPlan
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.EqResponse
import dev.continuum.fx.dsp.FxContext
import dev.continuum.fx.dsp.FxDirect
import dev.continuum.fx.dsp.FxSettings
import dev.continuum.fx.dsp.FxTone
import dev.continuum.fx.dsp.LimiterSpec
import dev.continuum.fx.dsp.MbcSpec
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Direct mode: the EQ and effects go on each app's own sound, with nothing captured and nothing
 * played back. Each playing app's session gets Android's DynamicsProcessing effect: [BandPlan]'s
 * ninety-six bands carry the EQ's curve and the effects' tone (each ear its own, for width), the
 * input gain the preamp (and any volume boost), the seven-band compressor the effects that move
 * with the music, and the limiter keeps boosts from clipping. An app with its own EQ gets that.
 *
 * Changes reach every app together, at most every few frames, and only what moved is sent, so
 * dragging a band doesn't flood the audio system.
 */
class DirectEq : SessionEffects {
    /** What one effect should be set to. */
    private class Plan(
        val on: Boolean,
        val inputDb: Float,
        val left: FloatArray,
        val right: FloatArray,
        val mbc: List<MbcSpec>,
        val limiter: LimiterSpec,
    ) {
        fun same(o: Plan?) = o != null && on == o.on && inputDb == o.inputDb && left.contentEquals(o.left) &&
            right.contentEquals(o.right) && mbc == o.mbc && limiter == o.limiter
    }

    private class Inputs(val library: Library, val fx: FxSettings, val ctx: FxContext, val compare: Boolean, val boostDb: Float)

    @Volatile
    private var inputs: Inputs? = null

    /** Plans by EQ (its id, or "" for none), worked out once per change. */
    private val plans = HashMap<String, Plan>()
    private val applied = WeakHashMap<DynamicsProcessing, Plan>()
    private val owners = WeakHashMap<DynamicsProcessing, String>()
    private val lock = Any()

    private val thread = HandlerThread("ContinuumDirectEq").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile
    private var pending = false

    /** Where to find the effects in place, between the watcher's checks. */
    var source: (() -> List<AudioEffect>)? = null

    override val respectsCaptureBlock = false

    override fun attach(sid: Int, pkg: String): AudioEffect? = try {
        val plan = synchronized(lock) { planFor(pkg) }
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, // Android copies the setup to however many channels the app plays.
            true, BandPlan.BANDS,
            true, FxDirect.CUTOFFS.size,
            false, 0,
            true,
        )
            // A longer frame resolves the bass more finely, at a few milliseconds' delay.
            .setPreferredFrameDuration(FRAME_MS)
            .setPreEqAllChannelsTo(eqOf(plan.left))
            .setMbcAllChannelsTo(mbcOf(plan.mbc))
            .setLimiterAllChannelsTo(limiterOf(plan.limiter))
            .setInputGainAllChannelsTo(plan.inputDb)
            .build()
        DynamicsProcessing(Int.MAX_VALUE, sid, config).also { dp ->
            synchronized(lock) {
                owners[dp] = pkg
                // The config gave every ear the left's bands, and a new effect starts switched
                // off: from there, only the right ear's differences and the switch remain.
                applied[dp] = Plan(false, plan.inputDb, plan.left, plan.left, plan.mbc, plan.limiter)
                bring(dp, plan)
            }
        }
    } catch (_: Throwable) {
        null
    }

    override fun synced(effects: Collection<AudioEffect>) = bringUp(effects)

    /** What to apply now: the library (each app's own EQ or the active one), the effects, compare and any boost. */
    fun update(library: Library, fx: FxSettings, ctx: FxContext, compare: Boolean, boostDb: Float) {
        val next = Inputs(library, fx, ctx.copy(direct = true), compare, boostDb)
        val prev = inputs
        if (prev != null && prev.library == library && prev.fx == fx && prev.ctx == next.ctx && prev.compare == compare && prev.boostDb == boostDb) return
        synchronized(lock) {
            inputs = next
            plans.clear()
        }
        if (!pending) {
            pending = true
            handler.postDelayed({
                pending = false
                bringUp(source?.invoke().orEmpty())
            }, BATCH_MS)
        }
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    /** The EQ for [pkg]: its own if it has one, else the active one; none when the EQ's off. */
    private fun profileFor(lib: Library, pkg: String?): EqProfile? = when {
        !lib.eqEnabled -> null
        else -> pkg?.let { lib.appProfile(it) } ?: lib.active
    }

    private fun planFor(pkg: String?): Plan {
        val i = inputs ?: return neutral
        val profile = profileFor(i.library, pkg)
        return plans.getOrPut(profile?.id ?: "") { build(profile, i) }
    }

    private fun build(profile: EqProfile?, i: Inputs): Plan {
        if (i.compare) {
            // Without the EQ or effects, at the loudness they had.
            val tone = FxTone.bands(i.fx, i.ctx)
            val both = profile?.let { FxTone.withTone(it, tone) } ?: EqProfile(name = "", bands = tone)
            val flat = FloatArray(BandPlan.BANDS)
            val loud = if (profile != null || tone.isNotEmpty()) EqResponse.loudnessDb(both).toFloat() else 0f
            return Plan(true, loud + i.boostDb, flat, flat, FxDirect.plan(FxSettings(enabled = false), i.ctx).mbc, LimiterSpec())
        }
        val fx = FxDirect.plan(i.fx, i.ctx)
        val bands = (profile?.bands ?: emptyList()) + fx.tone
        val gains = BandPlan.gains(bands)
        val width = fx.widthDb
        val left = if (width == null) gains else FloatArray(BandPlan.BANDS) { gains[it] + width[it] }
        val right = if (width == null) gains else FloatArray(BandPlan.BANDS) { gains[it] - width[it] }
        val input = ((profile?.preampDb ?: 0.0) + i.boostDb).toFloat()
        val on = profile != null || i.fx.anyOn || i.boostDb > 0f
        return Plan(on, input, left, right, fx.mbc, fx.limiter)
    }

    /** Brings every effect to its plan, sending only what differs from what it has. */
    private fun bringUp(effects: Collection<AudioEffect>) {
        synchronized(lock) {
            for (e in effects) {
                val dp = e as? DynamicsProcessing ?: continue
                bring(dp, planFor(owners[dp]))
            }
        }
    }

    private fun bring(dp: DynamicsProcessing, plan: Plan) {
        val have = applied[dp]
        if (plan.same(have)) return
        try {
            if (have == null || have.inputDb != plan.inputDb) dp.setInputGainAllChannelsTo(plan.inputDb)
            val channels = try {
                dp.channelCount
            } catch (_: Throwable) {
                2
            }
            for (i in 0 until BandPlan.BANDS) {
                val l = plan.left[i]
                val r = plan.right[i]
                val sameL = have != null && abs(have.left[i] - l) < 0.01f
                val sameR = have != null && abs(have.right[i] - r) < 0.01f
                if (sameL && sameR) continue
                if (l == r || channels < 2) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, BandPlan.cutoffs[i], if (channels < 2) (l + r) / 2 else l))
                } else {
                    // Left on even channels, right on odd: stereo, and front pairs of anything wider.
                    for (ch in 0 until channels) {
                        dp.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, BandPlan.cutoffs[i], if (ch % 2 == 0) l else r))
                    }
                }
            }
            for (b in plan.mbc.indices) {
                if (have != null && have.mbc.getOrNull(b) == plan.mbc[b]) continue
                dp.setMbcBandAllChannelsTo(b, mbcBandOf(plan.mbc[b]))
            }
            if (have == null || have.limiter != plan.limiter) dp.setLimiterAllChannelsTo(limiterOf(plan.limiter))
            if (have == null || have.on != plan.on) dp.setEnabled(plan.on)
            applied[dp] = plan
        } catch (_: Throwable) {
            // Released under us (the app stopped); the watcher drops it.
            applied.remove(dp)
        }
    }

    private fun eqOf(gains: FloatArray): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, BandPlan.BANDS)
        for (i in 0 until BandPlan.BANDS) eq.setBand(i, DynamicsProcessing.EqBand(true, BandPlan.cutoffs[i], gains[i]))
        return eq
    }

    private fun mbcOf(specs: List<MbcSpec>): DynamicsProcessing.Mbc {
        val mbc = DynamicsProcessing.Mbc(true, true, specs.size)
        specs.forEachIndexed { i, s -> mbc.setBand(i, mbcBandOf(s)) }
        return mbc
    }

    private fun mbcBandOf(s: MbcSpec) = DynamicsProcessing.MbcBand(
        !s.neutral, s.cutoff.toFloat(), s.attackMs.toFloat(), s.releaseMs.toFloat(), s.ratio.toFloat(),
        s.thresholdDb.toFloat(), s.kneeDb.toFloat(), s.gateDb.toFloat(), s.expanderRatio.toFloat(),
        s.preGainDb.toFloat(), s.postGainDb.toFloat(),
    )

    private fun limiterOf(l: LimiterSpec) = DynamicsProcessing.Limiter(
        true, true, 0, l.attackMs.toFloat(), l.releaseMs.toFloat(), l.ratio.toFloat(), l.thresholdDb.toFloat(), 0f,
    )

    private companion object {
        const val FRAME_MS = 20f
        const val BATCH_MS = 33L

        val neutral = Plan(
            true, 0f, FloatArray(BandPlan.BANDS), FloatArray(BandPlan.BANDS),
            FxDirect.plan(FxSettings(enabled = false), FxContext()).mbc, LimiterSpec(),
        )
    }
}
