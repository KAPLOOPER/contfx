package dev.continuum.keyboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Something you copied. Text, or an image kept in the keyboard's own storage. */
data class Clip(
    val id: Long,
    val text: String?,
    /** File name under clips/ for images. */
    val image: String?,
    val mime: String?,
    val time: Long,
    val pinned: Boolean,
)

/**
 * Clipboard history: everything you copy while Continuum Keyboard is your keyboard, kept
 * on the phone for as long as you choose. Pinned clips stay until you unpin them. Copies
 * marked sensitive (passwords and the like) are never kept.
 */
class ClipboardHistory private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "clipboard.json")
    private val dir = File(appContext.filesDir, "clips")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "continuum-clips") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Newest first. */
    @Volatile
    var clips: List<Clip> = emptyList()
        private set

    /** When the newest clip arrived, for the paste chip in the strip. */
    var lastAdded: Clip? = null
        private set

    init {
        clips = load()
    }

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    /** Reads the system clipboard after it changes and keeps what's on it. */
    fun capture(manager: ClipboardManager) {
        val clip: ClipData = try {
            manager.primaryClip ?: return
        } catch (e: SecurityException) {
            return
        }
        if (clip.itemCount == 0) return
        val desc = clip.description
        if (isSensitive(desc)) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        val imageMime = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        if (uri != null && imageMime != null) {
            io.execute { addImage(uri, imageMime) }
            return
        }
        val text = item.coerceToText(appContext)?.toString()?.takeIf { it.isNotBlank() } ?: return
        addText(text)
    }

    private fun isSensitive(desc: ClipDescription): Boolean {
        val extras: PersistableBundle = desc.extras ?: return false
        if (Build.VERSION.SDK_INT >= 33 && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) return true
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    @Synchronized
    fun addText(text: String) {
        val trimmed = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
        val existing = clips.firstOrNull { it.text == trimmed }
        val clip = Clip(System.currentTimeMillis(), trimmed, null, "text/plain", System.currentTimeMillis(), existing?.pinned ?: false)
        clips = listOf(clip) + clips.filter { it.text != trimmed }
        lastAdded = clip
        trim()
        save()
        changed()
    }

    private fun addImage(uri: Uri, mime: String) {
        try {
            dir.mkdirs()
            val id = System.currentTimeMillis()
            val ext = mime.substringAfter('/').substringBefore(';').take(5).ifBlank { "png" }
            val name = "$id.$ext"
            val target = File(dir, name)
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_IMAGE) {
                            target.delete()
                            return
                        }
                        out.write(buf, 0, n)
                    }
                }
            } ?: return
            synchronized(this) {
                val clip = Clip(id, null, name, mime, id, false)
                clips = listOf(clip) + clips
                lastAdded = clip
                trim()
                save()
            }
            changed()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't keep copied image", e)
        }
    }

    @Synchronized
    fun pin(id: Long, pinned: Boolean) {
        clips = clips.map { if (it.id == id) it.copy(pinned = pinned) else it }
        save(); changed()
    }

    @Synchronized
    fun remove(id: Long) {
        clips.firstOrNull { it.id == id }?.image?.let { File(dir, it).delete() }
        clips = clips.filter { it.id != id }
        if (lastAdded?.id == id) lastAdded = null
        save(); changed()
    }

    /** Removes everything except pinned clips. */
    @Synchronized
    fun clearUnpinned() {
        clips.filter { !it.pinned }.forEach { c -> c.image?.let { File(dir, it).delete() } }
        clips = clips.filter { it.pinned }
        lastAdded = null
        save(); changed()
    }

    /** Lets go of clips older than [keepMillis], unless pinned. */
    @Synchronized
    fun expire(keepMillis: Long) {
        if (keepMillis == Long.MAX_VALUE) return
        val cutoff = System.currentTimeMillis() - keepMillis
        val old = clips.filter { !it.pinned && it.time < cutoff }
        if (old.isEmpty()) return
        old.forEach { c -> c.image?.let { File(dir, it).delete() } }
        clips = clips - old.toSet()
        save(); changed()
    }

    fun forgetChip() {
        lastAdded = null
    }

    /** A content URI another app can read, for pasting an image clip. */
    fun uriFor(clip: Clip): Uri? {
        val name = clip.image ?: return null
        val f = File(dir, name)
        if (!f.exists()) return null
        return FileProvider.getUriForFile(appContext, AUTHORITY, f)
    }

    fun fileFor(clip: Clip): File? = clip.image?.let { File(dir, it) }?.takeIf { it.exists() }

    private fun trim() {
        val unpinned = clips.filter { !it.pinned }
        if (unpinned.size > MAX_CLIPS) {
            val drop = unpinned.drop(MAX_CLIPS).toSet()
            drop.forEach { c -> c.image?.let { File(dir, it).delete() } }
            clips = clips - drop
        }
    }

    private fun save() {
        val snapshot = clips
        io.execute {
            try {
                val arr = JSONArray()
                for (c in snapshot) {
                    arr.put(
                        JSONObject()
                            .put("id", c.id)
                            .put("text", c.text ?: JSONObject.NULL)
                            .put("image", c.image ?: JSONObject.NULL)
                            .put("mime", c.mime ?: JSONObject.NULL)
                            .put("time", c.time)
                            .put("pinned", c.pinned),
                    )
                }
                val tmp = File(file.path + ".tmp")
                tmp.writeText(arr.toString())
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save clipboard history", e)
            }
        }
    }

    private fun load(): List<Clip> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Clip(
                    id = o.getLong("id"),
                    text = if (o.isNull("text")) null else o.getString("text"),
                    image = if (o.isNull("image")) null else o.getString("image"),
                    mime = if (o.isNull("mime")) null else o.getString("mime"),
                    time = o.getLong("time"),
                    pinned = o.optBoolean("pinned"),
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read clipboard history", e)
        emptyList()
    }

    companion object {
        private const val TAG = "ContinuumClips"
        const val AUTHORITY = "dev.continuum.keyboard.files"
        private const val MAX_CLIPS = 60
        private const val MAX_TEXT = 20_000
        private const val MAX_IMAGE = 15L * 1024 * 1024

        @Volatile
        private var shared: ClipboardHistory? = null

        fun get(context: Context): ClipboardHistory =
            shared ?: synchronized(this) { shared ?: ClipboardHistory(context).also { shared = it } }
    }
}
