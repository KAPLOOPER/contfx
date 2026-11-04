package dev.continuum.fx.island

import android.content.Context
import android.provider.Settings
import android.util.Log
import dev.continuum.fx.setup.Privileges

/**
 * Android's own clock, battery and status icons step aside while the island carries the time
 * and the battery itself, so the bar shows each once. It's the list Android's System UI tuner
 * keeps ("icon_blacklist"): the island adds to whatever was in it, and puts it back exactly as it
 * was when it stops, when you turn the option off, or when the island is switched off.
 * Notification icons aren't on that list, so they stay. Needs the same one-time permission as
 * the pop-ups.
 */
object StatusIcons {
    private const val TAG = "StatusIcons"
    private const val KEY = "icon_blacklist"
    private const val FILE = "status_icons"
    private const val NONE = "\u0000"

    /** What the island stands in for, by the names System UI gives them. */
    private val HIDDEN = listOf(
        "clock", "battery", "wifi", "mobile", "airplane", "bluetooth", "volume", "zen", "alarm_clock",
        "location", "rotate", "headset", "hotspot", "cast", "nfc", "vpn", "data_saver", "managed_profile",
        "ethernet", "speakerphone", "mute", "ime", "sensors_off",
    )

    /** True while the island has Android's icons hidden. */
    fun hiding(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("hiding", false)

    /** Hides Android's icons ([hide]), or puts them back as they were. */
    @Synchronized
    fun sync(context: Context, hide: Boolean) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val hiding = prefs.getBoolean("hiding", false)
        if (hide == hiding) return
        try {
            if (hide) {
                if (!PopupGuard.allowed(context)) return
                val before = Settings.Secure.getString(context.contentResolver, KEY)
                prefs.edit().putBoolean("hiding", true).putString("before", before ?: NONE).commit()
                val mine = before.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                put(context, (mine + HIDDEN).distinct().joinToString(","))
            } else {
                val before = prefs.getString("before", NONE)
                put(context, before.takeIf { it != NONE })
                prefs.edit().putBoolean("hiding", false).commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't change Android's status icons", e)
        }
    }

    private fun put(context: Context, value: String?) {
        try {
            Settings.Secure.putString(context.contentResolver, KEY, value)
        } catch (_: Exception) {
            // Falls back to Shizuku when the settings call is refused.
            val command = if (value == null) "settings delete secure $KEY" else "settings put secure $KEY $value"
            Privileges.shizukuProcess(command)?.waitFor()
        }
    }
}
