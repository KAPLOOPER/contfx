package dev.continuum.fx.audio

import dev.continuum.fx.dsp.SpectrumTap
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The sound playing, made available to the screen for the live spectrum. Samples are only
 * copied here while someone is looking.
 */
object LiveAudio {
    val tap = SpectrumTap()

    /** The EQ graph is on screen and wants samples. */
    val listening = MutableStateFlow(false)

    /**
     * How many samples the spectrum reads at once: the capture engine fills the tap without
     * gaps, while Direct mode's visualizer hands over a short stretch at a time.
     */
    @Volatile
    var fftSize = 4096
}
