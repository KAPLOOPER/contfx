package dev.continuum.fx.audio

import dev.continuum.fx.dsp.SpectrumTap

/**
 * The processed sound, made available to the screen for the live spectrum. The engine
 * only copies samples here while someone is looking.
 */
object LiveAudio {
    val tap = SpectrumTap()

    @Volatile
    var listening = false
}
