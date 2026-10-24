package dev.continuum.fx.island

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class IslandAction(val title: String, val intent: PendingIntent?)

data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    /** Dominant colour of the artwork, used for the playing bars: content brings the colour. */
    val accent: Int,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** elapsedRealtime when [positionMs] was sampled, so the UI can extrapolate. */
    val sampledAt: Long,
    val speed: Float,
    val packageName: String,
    val demo: Boolean = false,
) {
    fun positionNow(): Long {
        if (!playing) return positionMs
        val p = positionMs + ((SystemClock.elapsedRealtime() - sampledAt) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p
    }
}

data class CallInfo(
    val key: String,
    val caller: String,
    val photo: Bitmap?,
    val ringing: Boolean,
    /** Wall-clock start of an ongoing call, when the dialer shares it. */
    val startedAt: Long?,
    val answer: PendingIntent?,
    val end: PendingIntent?,
    val open: PendingIntent?,
    val appName: String,
    val demo: Boolean = false,
)

data class TimerInfo(
    val key: String,
    val label: String,
    /** Notification `when`: the end time of a countdown, or the start of a stopwatch. */
    val base: Long,
    val countDown: Boolean,
    val open: PendingIntent?,
    val actions: List<IslandAction>,
    val appName: String,
    val demo: Boolean = false,
)

data class NoteInfo(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val largeIcon: Bitmap?,
    val smallIcon: Icon?,
    val open: PendingIntent?,
    val postedAt: Long,
    val demo: Boolean = false,
)

/** Everything the island can show, published by the notification listener. */
object IslandRepository {
    val media = MutableStateFlow<MediaInfo?>(null)
    val call = MutableStateFlow<CallInfo?>(null)
    val timer = MutableStateFlow<TimerInfo?>(null)
    val note = MutableStateFlow<NoteInfo?>(null)

    /** True while the overlay is attached by the accessibility service. */
    val hostActive = MutableStateFlow(false)

    /** True while the notification listener is connected. */
    val listenerActive = MutableStateFlow(false)

    @Volatile
    var mediaController: MediaController? = null

    @Volatile
    var listener: IslandNotificationListener? = null

    // ---- Media actions ----

    fun playPause() {
        val m = media.value ?: return
        if (m.demo) {
            media.value = m.copy(playing = !m.playing, positionMs = m.positionNow(), sampledAt = SystemClock.elapsedRealtime())
            return
        }
        val c = mediaController?.transportControls ?: return
        if (m.playing) c.pause() else c.play()
    }

    fun next() {
        if (media.value?.demo == true) return
        mediaController?.transportControls?.skipToNext()
    }

    fun previous() {
        if (media.value?.demo == true) return
        mediaController?.transportControls?.skipToPrevious()
    }

    fun seekTo(ms: Long) {
        val m = media.value ?: return
        if (m.demo) {
            media.value = m.copy(positionMs = ms, sampledAt = SystemClock.elapsedRealtime())
            return
        }
        mediaController?.transportControls?.seekTo(ms)
    }

    fun openMedia() {
        if (media.value?.demo == true) return
        send(mediaController?.sessionActivity)
    }

    // ---- Timer actions ----

    fun timerAction(t: TimerInfo, action: IslandAction) {
        if (!t.demo) {
            send(action.intent)
            return
        }
        when (action.title) {
            "+1:00" -> timer.value = t.copy(base = t.base + 60_000)
            else -> timer.value = null
        }
    }

    // ---- Call actions ----

    fun answer() {
        val c = call.value ?: return
        if (c.demo) {
            call.value = c.copy(ringing = false, startedAt = System.currentTimeMillis())
            return
        }
        send(c.answer)
    }

    fun endCall() {
        val c = call.value ?: return
        if (c.demo) {
            call.value = null
            return
        }
        send(c.end)
    }

    // ---- Notification actions ----

    fun dismissNote(n: NoteInfo) {
        if (note.value?.key == n.key) note.value = null
        if (!n.demo) listener?.dismiss(n.key)
    }

    fun send(intent: PendingIntent?) {
        try {
            intent?.send()
        } catch (_: Exception) {
        }
    }

    // ---- Demo items, so the island can be previewed without real events ----

    fun demo(kind: String) {
        val now = System.currentTimeMillis()
        when (kind) {
            "media" -> media.value = MediaInfo(
                title = "Night drive", artist = "Low Tide", art = null, accent = 0xFFD85A30.toInt(),
                playing = true, positionMs = 83_000, durationMs = 222_000,
                sampledAt = SystemClock.elapsedRealtime(), speed = 1f, packageName = "", demo = true,
            )
            "call" -> call.value = CallInfo(
                key = "demo-call", caller = "Priya Shah", photo = null, ringing = true, startedAt = null,
                answer = null, end = null, open = null, appName = "Phone", demo = true,
            )
            "timer" -> timer.value = TimerInfo(
                key = "demo-timer", label = "Timer", base = now + 5 * 60_000, countDown = true,
                open = null, actions = listOf(IslandAction("+1:00", null), IslandAction("Cancel", null)),
                appName = "Clock", demo = true,
            )
            "note" -> note.value = NoteInfo(
                key = "demo-note-$now", appName = "Messages", title = "Priya Shah",
                text = "Running five minutes late, save me a seat", largeIcon = null, smallIcon = null,
                open = null, postedAt = now, demo = true,
            )
            "clear" -> {
                if (media.value?.demo == true) media.value = null
                if (call.value?.demo == true) call.value = null
                if (timer.value?.demo == true) timer.value = null
                if (note.value?.demo == true) note.value = null
            }
        }
    }
}

/** Island preferences: the master switch and which sources it shows. */
data class IslandPrefs(
    val enabled: Boolean = true,
    val media: Boolean = true,
    val calls: Boolean = true,
    val timers: Boolean = true,
    val notifications: Boolean = true,
)

object IslandSettings {
    private const val FILE = "island"
    private val _state = MutableStateFlow(IslandPrefs())
    val state: StateFlow<IslandPrefs> = _state.asStateFlow()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = IslandPrefs(
            enabled = p.getBoolean("enabled", true),
            media = p.getBoolean("media", true),
            calls = p.getBoolean("calls", true),
            timers = p.getBoolean("timers", true),
            notifications = p.getBoolean("notifications", true),
        )
    }

    fun update(transform: (IslandPrefs) -> IslandPrefs) {
        val next = transform(_state.value)
        _state.value = next
        appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("enabled", next.enabled)
            ?.putBoolean("media", next.media)
            ?.putBoolean("calls", next.calls)
            ?.putBoolean("timers", next.timers)
            ?.putBoolean("notifications", next.notifications)
            ?.apply()
    }
}
