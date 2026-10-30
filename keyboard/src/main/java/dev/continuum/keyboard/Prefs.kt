package dev.continuum.keyboard

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Continuum Keyboard's settings, kept on the phone. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** "ink" (pure black) or "tint" (from the wallpaper, Android 12 and later). */
    var look by string("look", LOOK_INK)
    /** "compact", "normal" or "tall". */
    var keyHeight by string("key_height", HEIGHT_NORMAL)
    var numberRow by bool("number_row", false)
    var numberHints by bool("number_hints", false)
    var balloons by bool("balloons", true)

    var autocorrect by bool("autocorrect", true)
    var autoCaps by bool("auto_caps", true)
    var doubleSpace by bool("double_space", true)
    var vibrate by bool("vibrate", true)
    var sound by bool("sound", false)

    var swipe by bool("swipe", true)
    var swipeTrail by bool("swipe_trail", true)
    var deleteSwipe by bool("delete_swipe", true)
    var spaceCursor by bool("space_cursor", true)

    var clipboardHistory by bool("clipboard_history", true)
    /** "1h", "1d", "3d", "1w", "1m" or "forever". */
    var clipboardKeep by string("clipboard_keep", "3d")
    var clipboardChip by bool("clipboard_chip", true)

    /** Logins from your password manager as chips in the strip (Android 11 and later). */
    var passwordChips by bool("password_chips", true)

    /** One-time codes from Continuum FX, offered in the strip. */
    var codes by bool("codes", true)

    val clipboardKeepMillis: Long
        get() = when (clipboardKeep) {
            "1h" -> HOUR
            "1d" -> DAY
            "3d" -> 3 * DAY
            "1w" -> 7 * DAY
            "1m" -> 30 * DAY
            else -> Long.MAX_VALUE
        }

    val heightScale: Float
        get() = when (keyHeight) {
            HEIGHT_COMPACT -> 0.88f
            HEIGHT_TALL -> 1.12f
            else -> 1f
        }

    private fun bool(key: String, default: Boolean) = object : ReadWriteProperty<Prefs, Boolean> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getBoolean(key, default)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun string(key: String, default: String) = object : ReadWriteProperty<Prefs, String> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getString(key, default) ?: default
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply()
        }
    }

    companion object {
        const val NAME = "continuum_keyboard"
        const val LOOK_INK = "ink"
        const val LOOK_TINT = "tint"
        const val HEIGHT_COMPACT = "compact"
        const val HEIGHT_NORMAL = "normal"
        const val HEIGHT_TALL = "tall"
        private const val HOUR = 60L * 60 * 1000
        private const val DAY = 24 * HOUR
    }
}
