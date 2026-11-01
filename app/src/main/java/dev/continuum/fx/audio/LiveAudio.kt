package dev.continuum.fx.audio

import dev.continuum.fx.dsp.SpectrumTap
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The sound playing, made available to the screen for the live spectrum. Samples are only
 * copied here while someone is looking.
 */
object LiveAudio {
    val tap = SpectrumTap()

    /** Something on screen (the EQ graph, the dock) wants samples. */
    val listening = MutableStateFlow(false)

    private val listeners = java.util.concurrent.atomic.AtomicInteger(0)

    /** Asks for samples until the returned release is called; several can listen at once. */
    fun listen(): () -> Unit {
        listeners.incrementAndGet()
        listening.value = true
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true) && listeners.decrementAndGet() <= 0) {
                listeners.set(0)
                listening.value = false
            }
        }
    }

    /**
     * How many samples the spectrum reads at once: the capture engine fills the tap without
     * gaps, while Direct mode's visualizer hands over a short stretch at a time.
     */
    @Volatile
    var fftSize = 4096
}
