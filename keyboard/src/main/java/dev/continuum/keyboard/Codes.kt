package dev.continuum.keyboard

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * One-time codes from Continuum FX, which sees them arrive in your notifications. The keyboard
 * has no internet access and can't read notifications; Continuum FX hands it the latest fresh
 * code over Android's own channel between apps, open only to apps signed with the same key.
 */
object Codes {
    data class Offer(val code: String, val app: String, val at: Long)

    private val URI: Uri = Uri.parse("content://dev.continuum.fx.codes/latest")

    /** How long a code is offered for (as Continuum FX does). */
    const val FRESH_MS = 5 * 60_000L

    /** The fresh code, if Continuum FX has one (and lets us read it). */
    fun read(context: Context): Offer? = try {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val offer = Offer(c.getString(0), c.getString(1), c.getLong(2))
            offer.takeIf { System.currentTimeMillis() - it.at < FRESH_MS && it.code.isNotBlank() }
        }
    } catch (_: Exception) {
        // Continuum FX isn't installed, or this build can't read from it.
        null
    }

    /** Calls [onChange] whenever a new code arrives. Returns how to stop. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return try {
            context.contentResolver.registerContentObserver(URI, false, observer)
            { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
        } catch (_: Exception) {
            {}
        }
    }
}
