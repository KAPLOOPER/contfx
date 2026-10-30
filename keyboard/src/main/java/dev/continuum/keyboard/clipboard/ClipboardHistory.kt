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

/**
 * Something you copied. Text, or an image kept in the keyboard's own storage. A long text is
 * kept in a file of its own and read when it's pasted; everything that shows a clip uses its
 * [preview], so a copied essay costs no more to show than a copied word.
 */
data class Clip(
    val id: Long,
    /** The whole text, for short clips. Long ones are in [textFile]: read them with [ClipboardHistory.textOf]. */
    val text: String?,
    /** File name under clips/ for images. */
    val image: String?,
    val mime: String?,
    val time: Long,
    val pinned: Boolean,
    /** The start of the text, enough for a few lines. */
    val preview: String = text?.let { ClipboardHistory.previewOf(it) }.orEmpty(),
    /** How long the whole text is, in characters. */
    val length: Int = text?.length ?: 0,
    /** File name under clips/ holding a long text. */
    val textFile: String? = null,
    /** Of the whole text, to spot the same thing copied again without comparing it all. */
    val hash: Int = text?.hashCode() ?: 0,
)

/**
 * Clipboard history: everything you copy while Continuum Keyboard is your keyboard, kept
 * on the phone for as long as you choose. Pinned clips stay until you unpin them. Copies
 * marked sensitive (passwords and the like) are never kept.
 *
 * All the work (reading the history, keeping a copy, writing it out) happens on a thread of its
 * own, so copying something huge never makes the keyboard stutter.
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
    @Volatile
    var lastAdded: Clip? = null
        private set

    init {
        io.execute {
            val loaded = load()
            synchronized(this) {
                // Anything copied while it loaded goes on top.
                clips = clips + loaded.filter { l -> clips.none { it.id == l.id } }
            }
            moveLongTextsOut()
            changed()
        }
    }

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    /** Reads the system clipboard after it changes and keeps what's on it (the keeping is done off the main thread). */
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
        val plain = item.text
        if (plain != null) {
            // Already text: take it as it is and do the rest off the main thread.
            val text = plain.toString()
            if (text.isNotBlank()) io.execute { addText(text) }
            return
        }
        // Text behind a link or an intent: reading it can take a while, so not here.
        io.execute {
            val text = try {
                item.coerceToText(appContext)?.toString()
            } catch (e: Exception) {
                null
            }
            if (!text.isNullOrBlank()) addText(text)
        }
    }

    private fun isSensitive(desc: ClipDescription): Boolean {
        val extras: PersistableBundle = desc.extras ?: return false
        if (Build.VERSION.SDK_INT >= 33 && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) return true
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    /** Keeps [full] (cut to [MAX_TEXT]), on top. Runs on the clips thread. */
    private fun addText(full: String) {
        val text = if (full.length > MAX_TEXT) full.substring(0, MAX_TEXT) else full
        val hash = text.hashCode()
        val id = System.currentTimeMillis()
        val long = text.length > INLINE_MAX
        val name = if (long) "$id.txt" else null
        if (name != null) {
            try {
                dir.mkdirs()
                File(dir, name).writeText(text)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't keep a long clip", e)
                return
            }
        }
        synchronized(this) {
            val existing = clips.firstOrNull { it.image == null && it.length == text.length && it.hash == hash }
            existing?.let { deleteFiles(it) }
            val clip = Clip(
                id, if (long) null else text, null, "text/plain", id, existing?.pinned ?: false,
                preview = previewOf(text), length = text.length, textFile = name, hash = hash,
            )
            clips = listOf(clip) + clips.filter { it !== existing }
            lastAdded = clip
            trim()
            save()
        }
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

    /** The whole text of [clip]: in hand for short ones, read from its file for long ones. */
    fun textOf(clip: Clip): String? {
        clip.text?.let { return it }
        val name = clip.textFile ?: return null
        return try {
            File(dir, name).takeIf { it.exists() }?.readText()
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun pin(id: Long, pinned: Boolean) {
        clips = clips.map { if (it.id == id) it.copy(pinned = pinned) else it }
        save(); changed()
    }

    @Synchronized
    fun remove(id: Long) {
        clips.firstOrNull { it.id == id }?.let { deleteFiles(it) }
        clips = clips.filter { it.id != id }
        if (lastAdded?.id == id) lastAdded = null
        save(); changed()
    }

    /** Removes everything except pinned clips. */
    @Synchronized
    fun clearUnpinned() {
        clips.filter { !it.pinned }.forEach { deleteFiles(it) }
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
        old.forEach { deleteFiles(it) }
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

    private fun deleteFiles(c: Clip) {
        c.image?.let { File(dir, it).delete() }
        c.textFile?.let { File(dir, it).delete() }
    }

    private fun trim() {
        val unpinned = clips.filter { !it.pinned }
        if (unpinned.size > MAX_CLIPS) {
            val drop = unpinned.drop(MAX_CLIPS).toSet()
            drop.forEach { deleteFiles(it) }
            clips = clips - drop
        }
    }

    /** Earlier builds kept every text inside the history file: long ones move out to their own. */
    private fun moveLongTextsOut() {
        val long = clips.filter { it.text != null && it.text.length > INLINE_MAX }
        if (long.isEmpty()) return
        dir.mkdirs()
        val moved = HashMap<Long, Clip>()
        for (c in long) {
            val name = "${c.id}.txt"
            try {
                File(dir, name).writeText(c.text!!)
                moved[c.id] = c.copy(text = null, textFile = name)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't move a long clip out", e)
            }
        }
        synchronized(this) {
            clips = clips.map { moved[it.id] ?: it }
            lastAdded?.let { a -> moved[a.id]?.let { lastAdded = it } }
            save()
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
                            .put("pinned", c.pinned)
                            .put("preview", c.preview)
                            .put("length", c.length)
                            .put("textFile", c.textFile ?: JSONObject.NULL)
                            .put("hash", c.hash),
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
                val text = if (o.isNull("text")) null else o.getString("text")
                Clip(
                    id = o.getLong("id"),
                    text = text,
                    image = if (o.isNull("image")) null else o.getString("image"),
                    mime = if (o.isNull("mime")) null else o.getString("mime"),
                    time = o.getLong("time"),
                    pinned = o.optBoolean("pinned"),
                    preview = o.optString("preview").ifEmpty { text?.let { previewOf(it) }.orEmpty() },
                    length = o.optInt("length", text?.length ?: 0),
                    textFile = if (o.isNull("textFile") || !o.has("textFile")) null else o.getString("textFile"),
                    hash = if (o.has("hash")) o.getInt("hash") else text?.hashCode() ?: 0,
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
        /** About a million characters: longer than anything you'd paste, short enough to keep. */
        private const val MAX_TEXT = 1_000_000
        /** Texts longer than this live in a file of their own. */
        private const val INLINE_MAX = 4_000
        private const val PREVIEW = 300
        private const val MAX_IMAGE = 15L * 1024 * 1024

        /** The start of [text], for showing: a few lines' worth, never the whole of something long. */
        fun previewOf(text: String): String {
            val head = if (text.length > PREVIEW) text.substring(0, PREVIEW) else text
            return head.trim()
        }

        @Volatile
        private var shared: ClipboardHistory? = null

        fun get(context: Context): ClipboardHistory =
            shared ?: synchronized(this) { shared ?: ClipboardHistory(context).also { shared = it } }
    }
}
