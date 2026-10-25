package dev.continuum.fx.dsp

/** Punch: bass notes and kicks hit harder, sustained bass stays where it was. */
enum class PunchMode(val label: String, val maxDb: Double) {
    Off("Off", 0.0),
    Normal("Normal", 4.0),
    High("High", 7.0),
}

/** Dynamics: how peaks and quiet passages are looked after. */
enum class DynamicsMode(val label: String) {
    /** The usual safety limiter. */
    Off("Off"),

    /** The lightest touch that still keeps peaks clean: a soft 2:1 shoulder, no glue anywhere. */
    Pure("Pure"),

    /** For loud, squashed masters: quiet passages are let back down and the top opened up. */
    Restore("Restore"),
}

/** Stage: a wider, more open mix, with whatever's in the middle (voices) left there. */
enum class StageMode(val label: String) {
    Off("Off"),
    Natural("Natural"),
    Front("Front"),
    Wide("Wide"),
}

/**
 * What's being listened on, so bass and treble enhancement stay within what that kind of driver
 * can play, and crossfeed (a headphone effect) only runs on headphones. The scales are judgement,
 * not measurements: a sealed in-ear gets real bass from its seal, a small speaker has no cabinet
 * to make it, and every effect simply asks less of a driver that can give less.
 */
enum class Listener(val label: String, val bass: Double, val treble: Double, val headphones: Boolean) {
    Unsure("Not sure", 1.0, 1.0, true),
    InEar("In-ear", 0.85, 0.75, true),
    OnEar("On-ear", 0.6, 0.7, true),
    OverEar("Over-ear", 0.8, 0.8, true),
    Speaker("Speaker", 0.55, 0.85, false),

    /** The phone's own speaker: picked by itself, never offered. */
    PhoneSpeaker("Phone speaker", 0.45, 0.8, false),
}

/** Everything the effects are set to. Amounts run 0..1. */
data class FxSettings(
    /** The effects as a whole: off leaves only the EQ. */
    val enabled: Boolean = true,

    val tubeOn: Boolean = false,
    val tube: Double = 0.5,

    val bassOn: Boolean = false,
    val bassDrive: Double = 0.4,
    val bassWarmth: Double = 0.7,
    val bassDrift: Double = 0.2,
    val pultecBoost: Double = 0.5,
    val pultecCut: Double = 0.3,
    /** Index into [PULTEC_FREQS]. */
    val pultecFreq: Int = 2,

    val punch: PunchMode = PunchMode.Off,

    val speakerBassOn: Boolean = false,
    val speakerBass: Double = 0.5,

    val airOn: Boolean = false,
    val air: Double = 0.5,

    val exciterOn: Boolean = false,
    val exciter: Double = 0.5,

    val dynamics: DynamicsMode = DynamicsMode.Off,

    val loudnessOn: Boolean = false,
    val loudness: Double = 0.7,
    /** The loudness (phon) at full volume, where the mix sounds as it was balanced. */
    val loudnessRef: Double = 80.0,

    val stage: StageMode = StageMode.Off,

    val crossfeedOn: Boolean = false,
    val crossfeed: Double = 0.5,

    val bluetoothCare: Boolean = false,

    /** What each output is, by its key, when said. */
    val listeners: Map<String, Listener> = emptyMap(),
) {
    /** Whether anything would change the sound. */
    val anyOn: Boolean
        get() = enabled && (
            tubeOn || bassOn || punch != PunchMode.Off || speakerBassOn || airOn || exciterOn ||
                dynamics != DynamicsMode.Off || loudnessOn || stage != StageMode.Off || crossfeedOn || bluetoothCare
            )

    companion object {
        val PULTEC_FREQS = doubleArrayOf(20.0, 30.0, 60.0, 100.0)
        const val MIN_REF = 70.0
        const val MAX_REF = 90.0
    }
}

/** How the output kind reads for the effects (mirrors the audio layer's output kinds). */
enum class OutputKind { Speaker, Wired, Bluetooth, Usb, Other }

/**
 * Where the effects are playing: the output, what it is, and how far down the volume is.
 * [volumeDb] is the media volume's attenuation from full, 0 at the top and negative below.
 */
data class FxContext(
    val output: OutputKind = OutputKind.Other,
    val outputKey: String = "",
    val volumeDb: Double = 0.0,
    /** Direct mode: the effects run inside Android's own effect, which can't mix channels or make harmonics. */
    val direct: Boolean = false,
) {
    fun listener(fx: FxSettings): Listener =
        if (output == OutputKind.Speaker) Listener.PhoneSpeaker else fx.listeners[outputKey] ?: Listener.Unsure

    /** Headphones are on: crossfeed makes sense. */
    fun onHeadphones(fx: FxSettings): Boolean = output != OutputKind.Speaker && listener(fx).headphones

    val onPhoneSpeaker: Boolean get() = output == OutputKind.Speaker
    val onBluetooth: Boolean get() = output == OutputKind.Bluetooth
}

/** Why an effect isn't doing anything right now, if it isn't. */
object FxAvailability {
    fun speakerBass(ctx: FxContext): String? = if (!ctx.onPhoneSpeaker) "Works on the phone speaker; it rests on other outputs." else null

    fun crossfeed(fx: FxSettings, ctx: FxContext): String? = when {
        ctx.direct -> "Needs Capture: Direct mode can't mix one ear into the other."
        !ctx.onHeadphones(fx) -> "For headphones; it rests on speakers."
        else -> null
    }

    fun bluetoothCare(ctx: FxContext): String? = if (!ctx.onBluetooth) "Works on Bluetooth; it rests on other outputs." else null

    /** What Direct mode does instead, where it differs. */
    fun directNote(effect: String): String? = when (effect) {
        "tube" -> "In Direct mode: the tone and the glue, without the harmonics."
        "bass" -> "In Direct mode: the lift and a thicker low end, without the harmonics."
        "speakerBass" -> "In Direct mode: a lift where the speaker can play, without the harmonics."
        "exciter" -> "In Direct mode: brings up quiet detail rather than adding harmonics."
        "air" -> "In Direct mode: the air lift, without rebuilding the top octave."
        "stage" -> "In Direct mode: widens by shaping each ear's treble apart."
        else -> null
    }
}
