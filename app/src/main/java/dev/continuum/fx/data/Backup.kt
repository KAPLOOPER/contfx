package dev.continuum.fx.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything you've set up, in one file to keep or move to another phone: EQs and their links,
 * the effects, the island, the volume panel and each app's volume. Saved as the settings files
 * themselves, value by value with their types, so whatever a setting is, it comes back as it was.
 * Permissions aren't settings, and stay with the phone.
 */
object Backup {
    private const val FORMAT = "continuum-fx-backup"
    private const val VERSION = 1

    /** The settings files that make up a backup. */
    private val FILES = listOf("library", "effects", "spatial", "fit", "engine", "island", "volume", "app_volumes", "heard_apps", "ui")

    fun export(context: Context, build: Int): String {
        val prefs = JSONObject()
        for (file in FILES) {
            val values = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
            if (values.isEmpty()) continue
            val o = JSONObject()
            for ((key, value) in values) {
                val entry = when (value) {
                    is Boolean -> typed("b", value)
                    is Int -> typed("i", value)
                    is Long -> typed("l", value)
                    is Float -> typed("f", value.toDouble())
                    is String -> typed("s", value)
                    is Set<*> -> typed("ss", JSONArray(value.filterIsInstance<String>()))
                    else -> null
                } ?: continue
                o.put(key, entry)
            }
            prefs.put(file, o)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("build", build)
            .put("created", System.currentTimeMillis())
            .put("prefs", prefs)
            .toString(2)
    }

    private fun typed(t: String, v: Any) = JSONObject().put("t", t).put("v", v)

    /** What a backup holds, checked before anything is replaced. */
    class Contents internal constructor(internal val prefs: JSONObject, val created: Long, val eqCount: Int)

    /** Reads [text] as a backup, or says why it isn't one. */
    fun read(text: String): Result<Contents> = runCatching {
        val o = JSONObject(text)
        require(o.optString("format") == FORMAT) { "That file isn't a Continuum FX backup." }
        require(o.optInt("version") in 1..VERSION) { "That backup is from a newer Continuum FX. Update first." }
        val prefs = o.getJSONObject("prefs")
        val eqs = prefs.optJSONObject("library")?.optJSONObject("library_v1")?.optString("v")
            ?.let { runCatching { JSONObject(it).getJSONArray("profiles").length() }.getOrNull() } ?: 0
        Contents(prefs, o.optLong("created"), eqs)
    }.recoverCatching { e -> throw IllegalArgumentException(e.message?.takeIf { e is IllegalArgumentException } ?: "That file isn't a Continuum FX backup.") }

    /**
     * Replaces every setting with the backup's, all at once (each file is written before anything
     * reads it again). The app restarts afterwards to load them.
     */
    fun restore(context: Context, contents: Contents) {
        for (file in FILES) {
            val values = contents.prefs.optJSONObject(file)
            val edit = context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear()
            values?.keys()?.forEach { key ->
                val e = values.optJSONObject(key) ?: return@forEach
                when (e.optString("t")) {
                    "b" -> edit.putBoolean(key, e.optBoolean("v"))
                    "i" -> edit.putInt(key, e.optInt("v"))
                    "l" -> edit.putLong(key, e.optLong("v"))
                    "f" -> edit.putFloat(key, e.optDouble("v").toFloat())
                    "s" -> edit.putString(key, e.optString("v"))
                    "ss" -> edit.putStringSet(key, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
                }
            }
            edit.commit()
        }
    }
}
