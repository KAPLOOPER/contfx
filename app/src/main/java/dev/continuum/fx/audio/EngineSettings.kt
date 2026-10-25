package dev.continuum.fx.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the EQ reaches other apps' sound. */
enum class EngineMode {
    /** Capture what apps play and play it back processed (needs Android's capture prompt). */
    Capture,

    /** Put the EQ on each app's own sound; nothing captured, no prompt. */
    Direct,
}

object EngineSettings {
    private const val FILE = "engine"
    private val _mode = MutableStateFlow(EngineMode.Capture)
    val mode: StateFlow<EngineMode> = _mode.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("mode", null)
        _mode.value = EngineMode.entries.firstOrNull { it.name == saved } ?: EngineMode.Capture
    }

    fun setMode(mode: EngineMode) {
        _mode.value = mode
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()?.putString("mode", mode.name)?.apply()
    }
}

/** Stops processing, whichever way it's running. */
object Processing {
    fun stop(context: Context) {
        CaptureService.stop(context)
        DirectService.stop(context)
    }
}
