package dev.continuum.fx.audio

import android.media.audiofx.Visualizer

/**
 * The live spectrum in Direct mode, where nothing is captured: Android's visualizer on the
 * whole output (which needs only the microphone permission it's always asked for) hands over
 * a short stretch of what's playing at a time, and the EQ graph reads it as usual. Only runs
 * while the graph is on screen.
 */
class DirectSpectrum {
    private var viz: Visualizer? = null
    private var floats = FloatArray(0)

    fun listen(on: Boolean) {
        if (on == (viz != null)) return
        if (!on) {
            release()
            return
        }
        viz = try {
            val size = Visualizer.getCaptureSizeRange()[1].coerceAtMost(SIZE)
            Visualizer(0).apply {
                captureSize = size
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                        val w = waveform ?: return
                        if (floats.size != w.size) floats = FloatArray(w.size)
                        // Unsigned 8-bit, centred on 128.
                        for (i in w.indices) floats[i] = ((w[i].toInt() and 0xFF) - 128) / 128f
                        LiveAudio.tap.push(floats, floats.size, 1)
                    }

                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) = Unit
                }, Visualizer.getMaxCaptureRate(), true, false)
                enabled = true
            }.also { LiveAudio.fftSize = it.captureSize }
        } catch (_: Throwable) {
            // No microphone permission, or the visualizer is busy: the graph simply rests.
            null
        }
    }

    fun release() {
        try {
            viz?.enabled = false
            viz?.release()
        } catch (_: Throwable) {
        }
        viz = null
    }

    private companion object {
        const val SIZE = 1024
    }
}
