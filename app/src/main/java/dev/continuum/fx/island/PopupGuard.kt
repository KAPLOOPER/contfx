package dev.continuum.fx.island

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dev.continuum.fx.setup.Privileges

/**
 * Keeps Android's own notification pop-ups out of the way while the island shows new
 * notifications, so you only see Continuum's. Android has one switch for every app's
 * pop-ups; the island turns it off while it's running and puts it back exactly as it was
 * when it stops, when you turn the option off, or when the island is switched off.
 *
 * Changing that switch needs a permission granted once with Shizuku or ADB.
 */
object PopupGuard {
    private const val TAG = "PopupGuard"
    private const val KEY = "heads_up_notifications_enabled"
    private const val FILE = "popup_guard"
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

    fun allowed(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True while the island has Android's pop-ups switched off. */
    fun hiding(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("hiding", false)

    /** Switches Android's pop-ups off ([hide]) or back to how they were. */
    @Synchronized
    fun sync(context: Context, hide: Boolean) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val hiding = prefs.getBoolean("hiding", false)
        if (hide == hiding && !hide) return
        val resolver = context.contentResolver
        try {
            if (hide) {
                if (!allowed(context)) return
                if (!hiding) {
                    val before = read(context)
                    prefs.edit().putBoolean("hiding", true).putInt("before", before).commit()
                }
                if (read(context) != 0) write(context, 0)
            } else {
                val before = prefs.getInt("before", 1)
                if (allowed(context)) Settings.Global.putInt(resolver, KEY, before) else shellPut(before)
                prefs.edit().putBoolean("hiding", false).commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't change Android's pop-ups", e)
        }
    }

    private fun read(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, KEY, 1)
    } catch (_: Exception) {
        1
    }

    private fun write(context: Context, value: Int) {
        try {
            Settings.Global.putInt(context.contentResolver, KEY, value)
        } catch (_: Exception) {
            shellPut(value)
        }
    }

    /** Falls back to Shizuku when the settings call is refused. */
    private fun shellPut(value: Int) {
        Privileges.shizukuProcess("settings put global $KEY $value")?.waitFor()
    }

    /**
     * With pop-ups off, an alarm (or a call the island isn't showing) would only ring while
     * you're using the phone. Open its full-screen alert instead, as Android does when the
     * phone is locked, so nothing important is missed.
     */
    fun openFullScreen(context: Context, intent: PendingIntent) {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        // Locked or asleep, Android opens it itself.
        if (power?.isInteractive != true || keyguard?.isKeyguardLocked == true) return
        try {
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }
            intent.send(context, 0, null, null, null, null, options.toBundle())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the full-screen alert", e)
        }
    }
}
