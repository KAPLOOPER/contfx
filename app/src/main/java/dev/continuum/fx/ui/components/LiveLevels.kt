package dev.continuum.fx.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import dev.continuum.fx.audio.LiveAudio
import dev.continuum.fx.dsp.SpectrumAnalyzer

/** The music's level in each of [bands] bands (0..1), and a count that ticks with each new reading. */
internal class LiveLevels(bands: Int) {
    val levels = FloatArray(bands)
    var frame by mutableIntStateOf(0)
}

/**
 * The music playing, as band levels: read every other frame while [live], then left to sink to
 * rest. Samples are only copied out of the engine while something is listening.
 */
@Composable
internal fun rememberLiveLevels(live: Boolean, bands: Int): LiveLevels {
    val state = remember(bands) { LiveLevels(bands) }
    DisposableEffect(live) {
        val release = if (live) LiveAudio.listen() else null
        onDispose { release?.invoke() }
    }
    LaunchedEffect(live, bands) {
        var size = LiveAudio.fftSize
        var analyzer = SpectrumAnalyzer(fftSize = size, bands = bands)
        var samples = FloatArray(size)
        var last = 0L
        var odd = false
        while (true) {
            var resting = false
            withFrameNanos { t ->
                odd = !odd
                if (!odd) return@withFrameNanos
                val dt = if (last == 0L) 1f / 30f else ((t - last) / 1e9f).coerceIn(0.005f, 0.1f)
                last = t
                if (live) {
                    if (LiveAudio.fftSize != size) {
                        size = LiveAudio.fftSize
                        analyzer = SpectrumAnalyzer(fftSize = size, bands = bands)
                        samples = FloatArray(size)
                    }
                    LiveAudio.tap.latest(samples)
                    analyzer.analyze(samples, state.levels, dt)
                } else {
                    analyzer.settle(state.levels, dt)
                    resting = state.levels.all { it < 0.002f }
                }
                state.frame++
            }
            if (resting) break
        }
    }
    return state
}
