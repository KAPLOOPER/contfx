package dev.continuum.fx.dsp

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max

/**
 * The effects that move with the music, for Capture mode, where every sample passes through
 * here: harmonics, dynamics and the stereo image. (Plain tone lives in [FxTone] and runs with the
 * EQ.) Interleaved stereo float, in place.
 *
 * Settings arrive from the UI thread as a whole and are picked up at the start of the next chunk,
 * with no locks on the audio path. Every amount glides to its new value, and an effect switched
 * off fades out before it stops running, so nothing clicks. An effect that's off costs nothing.
 */
class FxChain(private val sr: Double = EqResponse.SAMPLE_RATE) {

    /** What the chain does, worked out from the settings. Zero means off. */
    data class Params(
        val speakerBass: Double = 0.0,
        val bass: Boolean = false,
        val bassDrive: Double = 0.0,
        val bassWarmth: Double = 0.0,
        val bassDrift: Double = 0.0,
        val punchDb: Double = 0.0,
        val tube: Double = 0.0,
        val glue: Boolean = false,
        val exciter: Double = 0.0,
        val airDb: Double = 0.0,
        val airRebuild: Double = 0.0,
        val stage: StageMode = StageMode.Off,
        val crossfeed: Double = 0.0,
        val expand: Boolean = false,
    ) {
        val anyOn: Boolean
            get() = speakerBass > 0 || bass || punchDb > 0 || tube > 0 || exciter > 0 || airDb > 0 || airRebuild > 0 ||
                stage != StageMode.Off || crossfeed > 0 || expand

        companion object {
            /** The chain for [fx] where [ctx] says it's playing. */
            fun of(fx: FxSettings, ctx: FxContext): Params {
                if (!fx.enabled) return Params()
                val l = ctx.listener(fx)
                return Params(
                    speakerBass = if (fx.speakerBassOn && ctx.onPhoneSpeaker) fx.speakerBass.coerceIn(0.0, 1.0) else 0.0,
                    bass = fx.bassOn,
                    bassDrive = if (fx.bassOn) fx.bassDrive.coerceIn(0.0, 1.0) else 0.0,
                    bassWarmth = if (fx.bassOn) fx.bassWarmth.coerceIn(0.0, 1.0) else 0.0,
                    bassDrift = if (fx.bassOn) fx.bassDrift.coerceIn(0.0, 1.0) else 0.0,
                    punchDb = fx.punch.maxDb * l.bass,
                    tube = if (fx.tubeOn) fx.tube.coerceIn(0.0, 1.0) else 0.0,
                    // Pure dynamics means nothing squeezes the music, the glue included.
                    glue = fx.tubeOn && fx.dynamics != DynamicsMode.Pure,
                    exciter = if (fx.exciterOn) fx.exciter.coerceIn(0.0, 1.0) else 0.0,
                    airDb = if (fx.airOn) 5.5 * fx.air.coerceIn(0.0, 1.0) * l.treble else 0.0,
                    airRebuild = if (fx.airOn) fx.air.coerceIn(0.0, 1.0) else 0.0,
                    stage = fx.stage,
                    crossfeed = if (fx.crossfeedOn && ctx.onHeadphones(fx)) fx.crossfeed.coerceIn(0.0, 1.0) else 0.0,
                    expand = fx.dynamics == DynamicsMode.Restore,
                )
            }
        }
    }

    private val pending = AtomicReference<Params?>(null)

    fun set(params: Params) = pending.set(params)

    fun set(fx: FxSettings, ctx: FxContext) = set(Params.of(fx, ctx))

    private val speakerBass = SpeakerBass()
    private val analogBass = AnalogBass()
    private val punch = Punch()
    private val tube = Tube()
    private val exciter = Exciter()
    private val air = Air()
    private val stage = Stage()
    private val crossfeed = Crossfeed()
    private val expander = Expander()

    /** Runs [frames] stereo frames through every effect that's on (or still fading out). */
    fun process(buf: FloatArray, frames: Int) {
        pending.getAndSet(null)?.let { p ->
            speakerBass.set(p.speakerBass)
            analogBass.set(p)
            punch.set(p.punchDb)
            tube.set(p.tube, p.glue)
            exciter.set(p.exciter)
            air.set(p.airDb, p.airRebuild)
            stage.set(p.stage)
            crossfeed.set(p.crossfeed)
            expander.set(p.expand)
        }
        speakerBass.process(buf, frames)
        analogBass.process(buf, frames)
        punch.process(buf, frames)
        tube.process(buf, frames)
        exciter.process(buf, frames)
        air.process(buf, frames)
        stage.process(buf, frames)
        crossfeed.process(buf, frames)
        expander.process(buf, frames)
    }

    /** True while any effect is running. */
    val busy: Boolean
        get() = !(speakerBass.idle && analogBass.idle && punch.idle && tube.idle && exciter.idle && air.idle &&
            stage.idle && crossfeed.idle && expander.idle)

    // ---- The effects ----

    /** The usual on/off fade, and a reset once fully off so the next start is clean. */
    private abstract inner class Effect(fadeMs: Double = 30.0) {
        protected val mix = Ramp(fadeMs, sr)
        private var running = false

        val idle: Boolean get() = mix.value == 0.0 && mix.target == 0.0

        fun process(buf: FloatArray, frames: Int) {
            if (idle) {
                if (running) {
                    running = false
                    reset()
                }
                return
            }
            running = true
            render(buf, frames)
        }

        protected abstract fun render(buf: FloatArray, frames: Int)
        protected abstract fun reset()
    }

    /**
     * The missing fundamental, for the phone's own speaker. It can't move enough air below a
     * couple of hundred hertz, so that bass is taken out (it only wastes the speaker's travel) and
     * its harmonics are put in where the speaker plays: hearing fills in the note from them.
     */
    private inner class SpeakerBass : Effect() {
        private val mainHp = arrayOf(Bq(Design.highPass(100.0, sr)), Bq(Design.highPass(100.0, sr)))
        private val srcHp = Bq(Design.highPass(40.0, sr))
        private val srcLp = Lr4(150.0, sr, high = false)
        private val gen = Harmonics(3.0, 120.0, sr)
        private val postHp = Bq(Design.highPass(120.0, sr))
        private val postLp = Bq(Design.lowPass(1200.0, sr))

        fun set(strength: Double) {
            mix.target = strength
        }

        override fun render(buf: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val a = mix.next()
                val i = 2 * f
                val l = buf[i].toDouble()
                val r = buf[i + 1].toDouble()
                val b = srcLp.run(srcHp.run(0.5 * (l + r)))
                val h = postLp.run(postHp.run(gen.run(b, 0.8, 0.55, 0.25))) * 1.3 * a
                val hl = mainHp[0].run(l)
                val hr = mainHp[1].run(r)
                buf[i] = (l + a * (hl - l) + h).toFloat()
                buf[i + 1] = (r + a * (hr - r) + h).toFloat()
            }
        }

        override fun reset() {
            mainHp.forEach { it.reset() }
            srcHp.reset(); srcLp.reset(); gen.reset(); postHp.reset(); postLp.reset()
        }
    }

    /**
     * Console bass: drive adds harmonics to the low end, warmth tips them from odd (edgier) to
     * even (rounder), and drift lets the colour and level wander very slightly, as old gear does.
     * The Pultec lift and cut are tone, in [FxTone].
     */
    private inner class AnalogBass : Effect() {
        private val drive = Glide(60.0, sr)
        private val warmth = Glide(60.0, sr)
        private var drift = 0.0
        private val lp = arrayOf(Lr4(120.0, sr, high = false), Lr4(120.0, sr, high = false))
        private val gen = arrayOf(Harmonics(5.0, 150.0, sr), Harmonics(5.0, 150.0, sr))
        private val hp = arrayOf(Bq(Design.highPass(25.0, sr)), Bq(Design.highPass(25.0, sr)))
        private val bias = Wander(sr, 0x5EEDL)
        private val wobble = Wander(sr, 0xB0B5L)

        fun set(p: Params) {
            mix.target = if (p.bass) 1.0 else 0.0
            drive.target = p.bassDrive
            warmth.target = p.bassWarmth
            drift = p.bassDrift
        }

        override fun render(buf: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val a = mix.next()
                val d = drive.next()
                val w = warmth.next()
                val db = bias.next() * drift
                val gw = 0.03 * wobble.next() * drift
                val c2 = d * (0.12 + 0.30 * w) * (1.0 + 0.5 * db)
                val c3 = d * (0.22 - 0.17 * w)
                for (ch in 0..1) {
                    val i = 2 * f + ch
                    val x = buf[i].toDouble()
                    val b = lp[ch].run(x)
                    val h = hp[ch].run(gen[ch].run(b, c2, c3))
                    buf[i] = (x + a * (h + gw * b)).toFloat()
                }
            }
        }

        override fun reset() {
            lp.forEach { it.reset() }; gen.forEach { it.reset() }; hp.forEach { it.reset() }
        }
    }

    /**
     * Punch: the bass band is lifted at the start of each note, by how much faster its level is
     * rising than its average, and left alone while it sustains. Kicks and plucked bass hit
     * harder; a held bass note doesn't swell.
     */
    private inner class Punch : Effect() {
        private var maxDb = 0.0
        private val lp = arrayOf(Bq(Design.lowPass(200.0, sr)), Bq(Design.lowPass(200.0, sr)))
        private val fast = Follower(1.5, 45.0, sr)
        private val slow = Follower(60.0, 280.0, sr)
        private val gain = Glide(2.0, sr, 1.0)

        fun set(db: Double) {
            if (db > 0) maxDb = db
            mix.target = if (db > 0) 1.0 else 0.0
        }

        override fun render(buf: FloatArray, frames: Int) {
            val k = ln(10.0) / 20.0
            for (f in 0 until frames) {
                val a = mix.next()
                val i = 2 * f
                val l = buf[i].toDouble()
                val r = buf[i + 1].toDouble()
                val bl = lp[0].run(l)
                val br = lp[1].run(r)
                val level = max(abs(bl), abs(br))
                val fs = fast.run(level)
                val sl = slow.run(level)
                val t = if (sl > 1e-6) ((fs / sl - 1.0) / 1.5).coerceIn(0.0, 1.0) else 0.0
                gain.target = exp(maxDb * t * a * k)
                val g = gain.next() - 1.0
                buf[i] = (l + g * bl).toFloat()
                buf[i + 1] = (r + g * br).toFloat()
            }
        }

        override fun reset() {
            lp.forEach { it.reset() }; fast.reset(); slow.reset(); gain.jump(1.0)
        }
    }

    /**
     * Tube warmth: low-order harmonics, second above third as valves give, on everything up to the
     * upper mids (so nothing lands past the top of the band), and a slow glue that rounds off the
     * dynamics. The glue's level is made up over a few seconds, so it's never quieter overall.
     */
    private inner class Tube : Effect(40.0) {
        private val amount = Glide(60.0, sr)
        private var glue = false
        private val glueMix = Glide(60.0, sr)
        private val lp = arrayOf(Lr4(5500.0, sr, high = false), Lr4(5500.0, sr, high = false))
        private val gen = arrayOf(Harmonics(8.0, 200.0, sr), Harmonics(8.0, 200.0, sr))
        private val dc = arrayOf(DcBlock(15.0, sr), DcBlock(15.0, sr))
        private val kRms = 1.0 - exp(-1.0 / (sr * 0.040))
        private val kAttack = 1.0 - exp(-1.0 / (sr * 0.030))
        private val kRelease = 1.0 - exp(-1.0 / (sr * 0.300))
        // The make-up follows the glue down within half a second, so it's never quieter for
        // long, and back up over a few seconds, so it doesn't pump.
        private val kAverageDown = 1.0 - exp(-1.0 / (sr * 0.4))
        private val kAverageUp = 1.0 - exp(-1.0 / (sr * 3.0))
        private var ms = 0.0
        private var gr = 0.0
        private var avg = 0.0

        fun set(t: Double, glue: Boolean) {
            mix.target = if (t > 0) 1.0 else 0.0
            if (t > 0) amount.target = t
            this.glue = glue && t > 0
            glueMix.target = if (this.glue) 1.0 else 0.0
        }

        override fun render(buf: FloatArray, frames: Int) {
            val k = ln(10.0) / 20.0
            for (f in 0 until frames) {
                val a = mix.next()
                val t = amount.next()
                val gm = glueMix.next() * a
                val c2 = 0.06 * t
                val c3 = 0.02 * t
                val i = 2 * f
                var l = buf[i].toDouble()
                var r = buf[i + 1].toDouble()
                l += a * dc[0].run(gen[0].run(lp[0].run(l), c2, c3))
                r += a * dc[1].run(gen[1].run(lp[1].run(r), c2, c3))
                if (gm > 0.0) {
                    ms += ((l * l + r * r) * 0.5 - ms) * kRms
                    val target = reduction(10.0 * log10(ms + 1e-12), 1.0 + 0.6 * t)
                    gr += (target - gr) * (if (target < gr) kAttack else kRelease)
                    avg += (gr - avg) * (if (gr < avg) kAverageDown else kAverageUp)
                    val g = exp((gr - avg.coerceAtLeast(-4.0)) * gm * k)
                    l *= g
                    r *= g
                }
                buf[i] = l.toFloat()
                buf[i + 1] = r.toFloat()
            }
        }

        /** Gain reduction (dB, at most 0) for a level, soft-kneed around -20 dBFS. */
        private fun reduction(levelDb: Double, ratio: Double): Double {
            val over = levelDb - THRESHOLD
            val slope = 1.0 - 1.0 / ratio
            return when {
                over <= -KNEE / 2 -> 0.0
                over >= KNEE / 2 -> -over * slope
                else -> -slope * (over + KNEE / 2) * (over + KNEE / 2) / (2 * KNEE)
            }
        }

        override fun reset() {
            lp.forEach { it.reset() }; gen.forEach { it.reset() }; dc.forEach { it.reset() }
            ms = 0.0; gr = 0.0; avg = 0.0
        }
    }

    /**
     * Exciter, after the BBE and Aphex boxes: the upper mids (2.2 to 7 kHz, where presence lives)
     * get harmonics of themselves, filtered so only the new, higher detail is added back. Voices
     * and guitars gain clarity and sparkle without a treble boost's hiss.
     */
    private inner class Exciter : Effect() {
        private val amount = Glide(60.0, sr)
        private val hp = arrayOf(Bq(Design.highPass(2200.0, sr)), Bq(Design.highPass(2200.0, sr)))
        private val lp = arrayOf(Bq(Design.lowPass(7000.0, sr)), Bq(Design.lowPass(7000.0, sr)))
        private val gen = arrayOf(Harmonics(0.5, 30.0, sr), Harmonics(0.5, 30.0, sr))
        private val post1 = arrayOf(Bq(Design.highPass(3200.0, sr)), Bq(Design.highPass(3200.0, sr)))
        private val post2 = arrayOf(Bq(Design.highPass(3200.0, sr)), Bq(Design.highPass(3200.0, sr)))

        fun set(t: Double) {
            mix.target = if (t > 0) 1.0 else 0.0
            if (t > 0) amount.target = t
        }

        override fun render(buf: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val m = mix.next() * amount.next() * 0.25
                for (ch in 0..1) {
                    val i = 2 * f + ch
                    val x = buf[i].toDouble()
                    val band = lp[ch].run(hp[ch].run(x))
                    val h = post2[ch].run(post1[ch].run(gen[ch].run(band, 0.6, 0.4)))
                    buf[i] = (x + m * h).toFloat()
                }
            }
        }

        override fun reset() {
            hp.forEach { it.reset() }; lp.forEach { it.reset() }; gen.forEach { it.reset() }
            post1.forEach { it.reset() }; post2.forEach { it.reset() }
        }
    }

    /**
     * Hi-res detail. The air above 5 kHz is lifted while there's music in it, and left alone when
     * all that's there is hiss, so it brings back detail without the noise floor. And where a lossy
     * file has cut its top octave away (most stop at 16 kHz), that octave is rebuilt from the one
     * below it, faintly, as its harmonics.
     */
    private inner class Air : Effect() {
        private var liftDb = 0.0
        private val rebuild = Glide(60.0, sr)
        private val hp = arrayOf(Bq(Design.highPass(5200.0, sr)), Bq(Design.highPass(5200.0, sr)))
        private val hiEnv = Follower(0.6, 60.0, sr)
        private val allEnv = Follower(1.0, 120.0, sr)
        private val gain = Glide(5.0, sr, 1.0)
        private val srcHp = arrayOf(Lr4(7500.0, sr, high = true), Lr4(7500.0, sr, high = true))
        private val srcLp = arrayOf(Lr4(11000.0, sr, high = false), Lr4(11000.0, sr, high = false))
        private val gen = arrayOf(Harmonics(0.3, 20.0, sr), Harmonics(0.3, 20.0, sr))
        private val post = arrayOf(Lr4(15500.0, sr, high = true), Lr4(15500.0, sr, high = true))
        private val topHp = Lr4(16000.0, sr, high = true)
        private val topEnv = Follower(5.0, 300.0, sr)
        private val midEnv = Follower(5.0, 300.0, sr)
        private var open = 0.0
        private var missing = 0.0
        private var count = 0

        fun set(db: Double, rebuildAmount: Double) {
            mix.target = if (db > 0 || rebuildAmount > 0) 1.0 else 0.0
            if (db > 0) liftDb = db
            rebuild.target = rebuildAmount
        }

        override fun render(buf: FloatArray, frames: Int) {
            val k = ln(10.0) / 20.0
            for (f in 0 until frames) {
                val a = mix.next()
                val rb = rebuild.next() * a
                val i = 2 * f
                var l = buf[i].toDouble()
                var r = buf[i + 1].toDouble()
                val hl = hp[0].run(l)
                val hr = hp[1].run(r)
                val hiLevel = hiEnv.run(max(abs(hl), abs(hr)))
                val allLevel = allEnv.run(max(abs(l), abs(r)))
                val sl = srcLp[0].run(srcHp[0].run(l))
                val srr = srcLp[1].run(srcHp[1].run(r))
                val mid = midEnv.run(max(abs(sl), abs(srr)))
                val top = topEnv.run(topHp.run(0.5 * (l + r)))
                if (count-- <= 0) {
                    // Decided every few samples: these follow the music, not each sample.
                    count = CONTROL
                    val hiDb = db(hiLevel)
                    open = smooth((hiDb + 66.0) / 12.0) * smooth((hiDb - db(allLevel) + 50.0) / 10.0)
                    missing = smooth((db(mid) - db(top) - 20.0) / 15.0) * smooth((db(mid) + 70.0) / 10.0)
                }
                gain.target = exp(liftDb * open * a * k)
                val g = gain.next() - 1.0
                l += g * hl
                r += g * hr
                if (rb > 0.0) {
                    val w = 0.12 * rb * missing
                    l += w * post[0].run(gen[0].run(sl, 1.0, 0.0))
                    r += w * post[1].run(gen[1].run(srr, 1.0, 0.0))
                }
                buf[i] = l.toFloat()
                buf[i + 1] = r.toFloat()
            }
        }

        override fun reset() {
            hp.forEach { it.reset() }; hiEnv.reset(); allEnv.reset(); gain.jump(1.0)
            srcHp.forEach { it.reset() }; srcLp.forEach { it.reset() }; gen.forEach { it.reset() }; post.forEach { it.reset() }
            topHp.reset(); topEnv.reset(); midEnv.reset(); open = 0.0; missing = 0.0; count = 0
        }
    }

    /**
     * Stage, by mid and side: what both ears share (voices, bass, the kick) is left in the middle,
     * and only the difference between the ears is widened, from the treble down. So the mix opens
     * up while voices stay exactly centred. Wide also gathers the deepest bass to the middle, where
     * it's tightest; Front narrows a touch and brings voices forward instead.
     */
    private inner class Stage : Effect(40.0) {
        private var mode = StageMode.Off
        private var next: StageMode? = null
        private var hiGain = 1.0
        private var loGain = 1.0
        private var presence = false
        private val sHp = Bq(Design.highPass(3000.0, sr))
        private val sLp = Bq(Design.lowPass(120.0, sr))
        private val pk = Bq(Design.peak(2200.0, 1.5, 0.9, sr))

        fun set(m: StageMode) {
            if (m == mode && next == null) return
            if (mode == StageMode.Off || idle) {
                apply(m)
            } else {
                // A different shape: fade this one out first, then bring the new one in.
                next = m
                mix.target = 0.0
            }
        }

        private fun apply(m: StageMode) {
            mode = m
            next = null
            val (hiDb, fc, loDb) = when (m) {
                StageMode.Off -> Triple(0.0, 3000.0, 0.0)
                StageMode.Natural -> Triple(2.0, 3000.0, 0.0)
                StageMode.Front -> Triple(-1.0, 400.0, 0.0)
                StageMode.Wide -> Triple(4.5, 700.0, -3.0)
            }
            hiGain = Math.pow(10.0, hiDb / 20.0)
            loGain = Math.pow(10.0, loDb / 20.0)
            presence = m == StageMode.Front
            sHp.c = Design.highPass(fc, sr)
            mix.target = if (m == StageMode.Off) 0.0 else 1.0
        }

        override fun render(buf: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val a = mix.next()
                if (a == 0.0 && next != null) apply(next!!)
                val i = 2 * f
                val l = buf[i].toDouble()
                val r = buf[i + 1].toDouble()
                var m = 0.5 * (l + r)
                var s = 0.5 * (l - r)
                s += a * (hiGain - 1.0) * sHp.run(s)
                s += a * (loGain - 1.0) * sLp.run(s)
                if (presence) m += a * (pk.run(m) - m)
                buf[i] = (m + s).toFloat()
                buf[i + 1] = (m - s).toFloat()
            }
        }

        override fun reset() {
            sHp.reset(); sLp.reset(); pk.reset()
        }
    }

    /**
     * Crossfeed for headphones: each ear hears a little of the other, low-passed and so slightly
     * late, as it would from speakers in front of you. Hard-panned sounds stop sitting inside one
     * ear. Anything in the middle comes out exactly as it went in: the direct path's treble is
     * raised by just what the crossfed bass adds.
     */
    private inner class Crossfeed : Effect() {
        private val lpL = OnePoleLp(700.0, sr)
        private val lpR = OnePoleLp(700.0, sr)

        fun set(s: Double) {
            mix.target = if (s > 0) Math.pow(10.0, (-14.0 + 9.5 * s) / 20.0) else 0.0
        }

        override fun render(buf: FloatArray, frames: Int) {
            for (f in 0 until frames) {
                val feed = mix.next()
                val i = 2 * f
                val l = buf[i].toDouble()
                val r = buf[i + 1].toDouble()
                val ll = lpL.run(l)
                val lr = lpR.run(r)
                val n = 1.0 / (1.0 + feed)
                buf[i] = ((l + feed * (l - ll) + feed * lr) * n).toFloat()
                buf[i + 1] = ((r + feed * (r - lr) + feed * ll) * n).toFloat()
            }
        }

        override fun reset() {
            lpL.reset(); lpR.reset()
        }
    }

    /**
     * Restore's gentle expander: below -38 dBFS, quiet passages are let back down a little
     * (1.12:1, at most 8 dB), undoing some of what loudness-war mastering lifted. Loud passages,
     * and peaks, are left alone.
     */
    private inner class Expander : Effect() {
        private val kRms = 1.0 - exp(-1.0 / (sr * 0.015))
        private val kDown = 1.0 - exp(-1.0 / (sr * 0.005))
        private val kUp = 1.0 - exp(-1.0 / (sr * 0.080))
        private var ms = 0.0
        private var g = 1.0
        private var target = 1.0
        private var count = 0

        fun set(on: Boolean) {
            mix.target = if (on) 1.0 else 0.0
        }

        override fun render(buf: FloatArray, frames: Int) {
            val k = ln(10.0) / 20.0
            for (f in 0 until frames) {
                val a = mix.next()
                val i = 2 * f
                val l = buf[i].toDouble()
                val r = buf[i + 1].toDouble()
                ms += ((l * l + r * r) * 0.5 - ms) * kRms
                if (count-- <= 0) {
                    count = CONTROL
                    val level = 10.0 * log10(ms + 1e-12)
                    val grDb = if (level < -38.0) max(-8.0, (level + 38.0) * 0.12) else 0.0
                    target = exp(grDb * a * k)
                }
                g += (target - g) * (if (target < g) kDown else kUp)
                buf[i] = (l * g).toFloat()
                buf[i + 1] = (r * g).toFloat()
            }
        }

        override fun reset() {
            ms = 0.0; g = 1.0; target = 1.0; count = 0
        }
    }

    private companion object {
        const val CONTROL = 16
        const val THRESHOLD = -20.0
        const val KNEE = 12.0

        fun db(x: Double) = 20.0 * log10(x + 1e-9)

        /** 0 below 0, 1 above 1, smooth between. */
        fun smooth(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}
