package dev.continuum.fx.dsp

import kotlin.math.max
import kotlin.math.min

/**
 * The parts of the effects that are simply tone: fixed filters, the same in both ears. They run
 * with the EQ's own filters (in Capture mode) or join its 96 bands (in Direct mode), so they cost
 * nothing extra and both modes sound alike. What moves with the music, or adds harmonics, lives
 * in [FxChain] and [FxDirect].
 */
object FxTone {
    fun bands(fx: FxSettings, ctx: FxContext): List<EqBand> {
        if (!fx.enabled) return emptyList()
        val listener = ctx.listener(fx)
        val out = ArrayList<EqBand>()

        if (fx.tubeOn) {
            val t = fx.tube.coerceIn(0.0, 1.0)
            // Transformer bloom in the low end, and the soft top of an output transformer.
            out += EqBand(FilterType.PEAK, 80.0, 2.5 * t * listener.bass, 0.8)
            out += EqBand(FilterType.HIGH_SHELF, 11000.0, -3.0 * t, 0.6)
        }

        if (fx.bassOn) {
            val f = FxSettings.PULTEC_FREQS[fx.pultecFreq.coerceIn(0, FxSettings.PULTEC_FREQS.size - 1)]
            // The Pultec trick: lift and cut at once. The low end swells while the region just
            // above it, where bass turns to mud, dips away.
            out += EqBand(FilterType.LOW_SHELF, f * 1.6, 8.0 * fx.pultecBoost.coerceIn(0.0, 1.0) * listener.bass, 0.6)
            if (fx.pultecCut > 0.0) {
                out += EqBand(FilterType.PEAK, (f * 3.0).coerceIn(90.0, 400.0), -5.0 * fx.pultecCut.coerceIn(0.0, 1.0), 0.8)
            }
            out += EqBand(FilterType.PEAK, 110.0, 1.5 * fx.bassWarmth.coerceIn(0.0, 1.0) * listener.bass, 0.7)
        }

        if (fx.speakerBassOn && ctx.onPhoneSpeaker) {
            val s = fx.speakerBass.coerceIn(0.0, 1.0)
            if (ctx.direct) {
                // No harmonics here: keep the speaker from wasting itself below what it can play,
                // and lift where it still can.
                out += EqBand(FilterType.HIGH_PASS, 90.0, 0.0, 0.7)
                out += EqBand(FilterType.PEAK, 200.0, 5.0 * s, 0.8)
            } else {
                out += EqBand(FilterType.PEAK, 220.0, 2.0 * s, 0.9)
            }
        }

        if (fx.loudnessOn) {
            val now = Loudness.phonAt(fx.loudnessRef, ctx.volumeDb)
            out += Loudness.bands(now, fx.loudnessRef, fx.loudness.coerceIn(0.0, 1.0))
        }

        when (fx.stage) {
            StageMode.Off -> Unit
            // A smile: more low end and air, with nothing added where voices and mids live.
            StageMode.Natural -> smile(out, 2.5, listener)
            StageMode.Front -> {
                smile(out, 1.5, listener)
                // In Capture mode the presence lift is put on the middle only, by [FxChain].
                if (ctx.direct) out += EqBand(FilterType.PEAK, 2200.0, 1.5, 0.9)
            }
            StageMode.Wide -> smile(out, 3.5, listener)
        }

        if (fx.dynamics == DynamicsMode.Restore) {
            // Squashed masters lose their air along with their peaks.
            out += EqBand(FilterType.HIGH_SHELF, 10000.0, 0.6 * listener.treble, 0.7)
            out += EqBand(FilterType.HIGH_SHELF, 16000.0, 0.4 * listener.treble, 0.7)
        }

        if (fx.bluetoothCare && ctx.onBluetooth) {
            // Bluetooth's basic codec (SBC) shares a fixed budget of bits across the spectrum, every
            // few milliseconds. Easing off the top octave, which few can hear, leaves more for
            // the music below and steadies the swishy cymbals it's known for; a touch goes back
            // where hearing is keen. Other codecs barely notice.
            out += EqBand(FilterType.HIGH_SHELF, 16500.0, -6.0, 0.7)
            out += EqBand(FilterType.PEAK, 10000.0, 0.8, 1.0)
        }
        return out.filter { it.type != FilterType.PEAK && it.type != FilterType.LOW_SHELF && it.type != FilterType.HIGH_SHELF || kotlin.math.abs(it.gainDb) >= 0.05 }
    }

    private fun smile(out: MutableList<EqBand>, db: Double, listener: Listener) {
        out += EqBand(FilterType.LOW_SHELF, 55.0, db * min(1.0, listener.bass + 0.2), 0.7)
        out += EqBand(FilterType.HIGH_SHELF, 10000.0, db * min(1.0, listener.treble + 0.1), 0.7)
    }

    /** The EQ with the effects' tone added, as one profile. */
    fun withTone(profile: EqProfile, tone: List<EqBand>): EqProfile =
        if (tone.isEmpty()) profile else profile.copy(bands = profile.bands + tone)

    /** Highest lift the tone adds anywhere, for headroom notes. */
    fun peakDb(tone: List<EqBand>): Double = max(0.0, EqResponse.peakDb(EqProfile(name = "", bands = tone)))
}
