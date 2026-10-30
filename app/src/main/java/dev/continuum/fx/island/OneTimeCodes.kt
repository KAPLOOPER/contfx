package dev.continuum.fx.island

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle

/**
 * The latest one-time code, kept for a few minutes and only in memory: the island shows it with
 * a Copy button, and Continuum Keyboard offers it in its strip. Nothing is written down.
 */
object OneTimeCodes {
    data class Offer(val code: String, val app: String, val at: Long)

    /** How long a code is offered for. Most expire within five minutes anyway. */
    const val FRESH_MS = 5 * 60_000L

    @Volatile
    private var latest: Offer? = null

    /** The code to offer now, if there's a fresh one and codes are on. */
    fun current(): Offer? {
        if (!IslandSettings.state.value.codes) return null
        return latest?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }
    }

    fun offer(context: Context, code: String, app: String, at: Long) {
        latest = Offer(code, app, at)
        try {
            context.contentResolver.notifyChange(CodesProvider.URI, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Puts [code] on the clipboard, marked sensitive: Android hides it from clipboard previews,
     * and clipboard histories (Continuum Keyboard's included) don't keep it.
     */
    fun copy(context: Context, code: String): Boolean = try {
        val clip = ClipData.newPlainText("One-time code", code)
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Hands the latest code to Continuum Keyboard, which has no internet access and no way to read
 * notifications itself. Only apps signed with the same key as this one can read it (the
 * permission is signature-level), and it only ever holds the one fresh code.
 */
class CodesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(COL_CODE, COL_APP, COL_AT))
        OneTimeCodes.current()?.let { cursor.addRow(arrayOf<Any>(it.code, it.app, it.at)) }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.continuum.code"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "dev.continuum.fx.codes"
        val URI: Uri = Uri.parse("content://$AUTHORITY/latest")
        const val COL_CODE = "code"
        const val COL_APP = "app"
        const val COL_AT = "at"
    }
}
