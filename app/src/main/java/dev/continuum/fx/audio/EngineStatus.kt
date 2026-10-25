package dev.continuum.fx.audio

import kotlinx.coroutines.flow.MutableStateFlow

/** Live state of the capture service, shared with the UI. */
object EngineStatus {
    /** True while the capture service is running. */
    val running = MutableStateFlow(false)

    /** Last error worth showing to the person, or null. */
    val error = MutableStateFlow<String?>(null)

    /** Labels of apps whose audio is currently being processed. */
    val processing = MutableStateFlow<List<String>>(emptyList())

    /** Labels of apps that block audio capture, so they play unprocessed. */
    val blocked = MutableStateFlow<List<String>>(emptyList())

    /** Held down to hear the music without the EQ (at matched loudness), for comparison. */
    val comparing = MutableStateFlow(false)
}
