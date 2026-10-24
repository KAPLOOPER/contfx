package dev.continuum.fx.keyboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Options for the Continuum keyboard. They're saved now so the keyboard picks them up
 * the moment it arrives.
 */
data class KeyboardPrefs(
    /** Keys squash on Snap and a drop of ink rises with the letter. */
    val inkPress: Boolean = true,
    val haptics: String = HAPTICS_LIGHT,
    val sounds: Boolean = false,
    val height: String = HEIGHT_REGULAR,
    val numberRow: Boolean = true,
    val suggestions: Boolean = true,
    val dictation: Boolean = true,
    /** Slide along the space bar to move the cursor. */
    val spaceCursor: Boolean = true,
    val theme: String = THEME_SYSTEM,
) {
    companion object {
        const val HAPTICS_OFF = "off"
        const val HAPTICS_LIGHT = "light"
        const val HAPTICS_FIRM = "firm"
        const val HEIGHT_COMPACT = "compact"
        const val HEIGHT_REGULAR = "regular"
        const val HEIGHT_TALL = "tall"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_INK = "ink"
    }
}

object KeyboardSettings {
    private const val FILE = "keyboard"
    private val _state = MutableStateFlow(KeyboardPrefs())
    val state: StateFlow<KeyboardPrefs> = _state.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = KeyboardPrefs()
        _state.value = KeyboardPrefs(
            inkPress = p.getBoolean("inkPress", d.inkPress),
            haptics = p.getString("haptics", null) ?: d.haptics,
            sounds = p.getBoolean("sounds", d.sounds),
            height = p.getString("height", null) ?: d.height,
            numberRow = p.getBoolean("numberRow", d.numberRow),
            suggestions = p.getBoolean("suggestions", d.suggestions),
            dictation = p.getBoolean("dictation", d.dictation),
            spaceCursor = p.getBoolean("spaceCursor", d.spaceCursor),
            theme = p.getString("theme", null) ?: d.theme,
        )
    }

    fun update(transform: (KeyboardPrefs) -> KeyboardPrefs) {
        val next = transform(_state.value)
        _state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("inkPress", next.inkPress)
            ?.putString("haptics", next.haptics)
            ?.putBoolean("sounds", next.sounds)
            ?.putString("height", next.height)
            ?.putBoolean("numberRow", next.numberRow)
            ?.putBoolean("suggestions", next.suggestions)
            ?.putBoolean("dictation", next.dictation)
            ?.putBoolean("spaceCursor", next.spaceCursor)
            ?.putString("theme", next.theme)
            ?.apply()
    }
}
