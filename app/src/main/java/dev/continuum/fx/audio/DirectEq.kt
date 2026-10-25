package dev.continuum.fx.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.HandlerThread
import dev.continuum.fx.data.Library
import dev.continuum.fx.dsp.BandPlan
import dev.continuum.fx.dsp.EqProfile
import dev.continuum.fx.dsp.EqResponse
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * Direct mode: the EQ goes on each app's own sound, with nothing captured and nothing played
 * back. Each playing app's session gets Android's DynamicsProcessing effect with [BandPlan]'s
 * ninety-six bands set to the EQ's curve, the preamp (and any volume boost) as its input gain,
 * and its limiter keeping boosts from clipping.
 *
 * Changes reach every app together, at most every few frames, and only the bands that moved
 * are sent, so dragging a band doesn't flood the audio system.
 */
class DirectEq : SessionEffects {
    /** What every effect should be set to. */
    private class Plan(val on: Boolean, val inputDb: Float, val gains: FloatArray) {
        fun same(o: Plan?) = o != null && on == o.on && inputDb == o.inputDb && gains.contentEquals(o.gains)
    }

    @Volatile
    private var target = Plan(true, 0f, FloatArray(BandPlan.BANDS))
    private val applied = WeakHashMap<DynamicsProcessing, Plan>()
    private val lock = Any()

    private val thread = HandlerThread("ContinuumDirectEq").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile
    private var pending = false

    /** Where to find the effects in place, between the watcher's checks. */
    var source: (() -> List<AudioEffect>)? = null

    override val respectsCaptureBlock = false

    override fun attach(sid: Int): AudioEffect? = try {
        val plan = target
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2, // Android copies the setup to however many channels the app plays.
            true, BandPlan.BANDS,
            false, 0,
            false, 0,
            true,
        )
            // A longer frame resolves the bass more finely, at a few milliseconds' delay.
            .setPreferredFrameDuration(FRAME_MS)
            .setPreEqAllChannelsTo(eqOf(plan))
            .setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, LIMIT_DB, 0f))
            .setInputGainAllChannelsTo(plan.inputDb)
            .build()
        DynamicsProcessing(Int.MAX_VALUE, sid, config).also { dp ->
            dp.setEnabled(plan.on)
            synchronized(lock) { applied[dp] = plan }
        }
    } catch (_: Throwable) {
        null
    }

    override fun synced(effects: Collection<AudioEffect>) = bringUp(effects)

    /** The EQ to apply now: the library's, the compare hold's or none, plus any boost. */
    fun update(library: Library, compare: Boolean, boostDb: Float) {
        val profile: EqProfile? = when {
            !library.eqEnabled -> null
            compare -> EqProfile(name = "Original", preampDb = EqResponse.loudnessDb(library.active))
            else -> library.active
        }
        val gains = profile?.let { BandPlan.gains(it) } ?: FloatArray(BandPlan.BANDS)
        val input = ((profile?.preampDb ?: 0.0) + boostDb).toFloat()
        val next = Plan(profile != null || boostDb > 0f, input, gains)
        if (next.same(target)) return
        target = next
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

    /** Brings every effect to [target], sending only what differs from what it has. */
    private fun bringUp(effects: Collection<AudioEffect>) {
        val plan = target
        synchronized(lock) {
            for (e in effects) {
                val dp = e as? DynamicsProcessing ?: continue
                val have = applied[dp]
                if (plan.same(have)) continue
                try {
                    if (have == null || have.inputDb != plan.inputDb) dp.setInputGainAllChannelsTo(plan.inputDb)
                    for (i in 0 until BandPlan.BANDS) {
                        val g = plan.gains[i]
                        if (have != null && abs(have.gains[i] - g) < 0.01f) continue
                        dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, BandPlan.cutoffs[i], g))
                    }
                    if (have == null || have.on != plan.on) dp.setEnabled(plan.on)
                    applied[dp] = plan
                } catch (_: Throwable) {
                    // Released under us (the app stopped); the watcher drops it.
                    applied.remove(dp)
                }
            }
        }
    }

    private fun eqOf(plan: Plan): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, BandPlan.BANDS)
        for (i in 0 until BandPlan.BANDS) eq.setBand(i, DynamicsProcessing.EqBand(true, BandPlan.cutoffs[i], plan.gains[i]))
        return eq
    }

    private companion object {
        const val FRAME_MS = 20f
        const val BATCH_MS = 33L
        /** Just under full scale, so boosted bands are held back rather than clipped. */
        const val LIMIT_DB = -1f
    }
}
