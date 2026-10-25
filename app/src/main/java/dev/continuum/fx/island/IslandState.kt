package dev.continuum.fx.island

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

/** A button from a notification. With [inputs], it takes a typed reply. */
data class NoteAction(
    val title: String,
    val intent: PendingIntent?,
    val inputs: List<RemoteInput> = emptyList(),
) {
    val isReply: Boolean get() = inputs.isNotEmpty()
}

data class NoteInfo(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    val largeIcon: Bitmap?,
    val smallIcon: Icon?,
    val open: PendingIntent?,
    val postedAt: Long,
    /** The notification's own buttons, Reply first when it has one. */
    val actions: List<NoteAction> = emptyList(),
    /** Whether swiping can clear it from the shade. */
    val clearable: Boolean = true,
    val demo: Boolean = false,
)

/** The next alarm set on the phone. */
data class AlarmInfo(
    /** Wall-clock time it goes off. */
    val at: Long,
    /** Opens the alarm in its clock app. */
    val show: PendingIntent?,
    val demo: Boolean = false,
)

/** An alarm, or a timer that's finished, ringing now. */
data class AlarmRing(
    val key: String,
    val title: String,
    val text: String,
    val appName: String,
    val smallIcon: Icon?,
    /** Snooze, Stop and the like, as the clock app offers them. */
    val actions: List<NoteAction>,
    val open: PendingIntent?,
    val demo: Boolean = false,
)

/** Something under way: a download, directions, a delivery or a ride. */
data class LiveInfo(
    val key: String,
    val appName: String,
    val title: String,
    val text: String,
    /** A few characters for the pill ("4 min", "200 m"), when the app gives one. */
    val short: String?,
    /** 0..1, or a negative number while the app can't say how far along it is; null without a bar. */
    val progress: Float?,
    val smallIcon: Icon?,
    /** The app's colour for the notification, when it set one. */
    val accent: Int?,
    val open: PendingIntent?,
    val actions: List<NoteAction>,
    val updatedAt: Long,
    val demo: Boolean = false,
)

/** The ring/vibrate/silent switch just changed. [mode] is an AudioManager.RINGER_MODE_* value. */
data class SoundMode(val mode: Int, val at: Long)

data class BatteryInfo(
    /** 0..100 */
    val level: Int,
    val charging: Boolean,
    /** BatteryManager.BATTERY_PLUGGED_* or 0 when on battery. */
    val plugged: Int,
    val full: Boolean,
    /** Estimated time to full, when Android has one. */
    val fullInMs: Long?,
    val demo: Boolean = false,
) {
    val wireless: Boolean get() = plugged == 4
    val low: Boolean get() = plugged == 0 && level <= 20
}

/** Everything the island can show, published by the notification listener. */
object IslandRepository {
    val media = MutableStateFlow<MediaInfo?>(null)
    val call = MutableStateFlow<CallInfo?>(null)
    val timer = MutableStateFlow<TimerInfo?>(null)
    val note = MutableStateFlow<NoteInfo?>(null)

    /** The next alarm, from the clock. */
    val alarm = MutableStateFlow<AlarmInfo?>(null)
    val alarmDemo = MutableStateFlow<AlarmInfo?>(null)

    /** An alarm or finished timer ringing right now. */
    val alarmRing = MutableStateFlow<AlarmRing?>(null)

    /** The most recent thing under way: a download, directions, a delivery. */
    val live = MutableStateFlow<LiveInfo?>(null)

    /** The last time the ring/vibrate/silent switch changed. */
    val sound = MutableStateFlow<SoundMode?>(null)

    /** Notifications still in the shade, newest first, for pulling a drop out of the island. */
    val recent = MutableStateFlow<List<NoteInfo>>(emptyList())

    val battery = MutableStateFlow<BatteryInfo?>(null)
    val batteryDemo = MutableStateFlow<BatteryInfo?>(null)

    /** When the battery last had something to say: plugged in, running low, or full. */
    val batteryFlash = MutableStateFlow(0L)

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

    /** Clears [n] from the island and from the notification shade. */
    fun dismissNote(n: NoteInfo) {
        if (note.value?.key == n.key) note.value = null
        recent.value = recent.value.filterNot { it.key == n.key }
        if (!n.demo) listener?.dismiss(n.key)
    }

    /** Puts [n] away: off the island, but still in the shade and the pulled drop. */
    fun tuckNote(n: NoteInfo) {
        if (note.value?.key == n.key) note.value = null
    }

    /** Clears every notification that can be cleared, like the shade's Clear all. */
    fun clearAll() {
        note.value = null
        val real = recent.value.any { !it.demo }
        recent.value = recent.value.filter { !it.clearable && !it.demo }
        if (real) listener?.clearAll()
    }

    /** Taps one of a notification's buttons. Replies open the reply card instead. */
    fun noteAction(context: Context, n: NoteInfo, action: NoteAction) {
        if (action.isReply) {
            startReply(context, n, action)
            return
        }
        if (note.value?.key == n.key) note.value = null
        if (n.demo) {
            recent.value = recent.value.filterNot { it.key == n.key }
            return
        }
        send(action.intent)
    }

    /** The reply being written, held for the reply card. */
    @Volatile
    var replying: Pair<NoteInfo, NoteAction>? = null
        private set

    fun startReply(context: Context, n: NoteInfo, action: NoteAction) {
        replying = n to action
        if (note.value?.key == n.key) note.value = null
        try {
            context.startActivity(
                Intent(context, ReplyActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        } catch (_: Exception) {
            send(n.open)
        }
    }

    /** Sends [text] as the reply, the way the app's own Reply button would. */
    fun sendReply(context: Context, n: NoteInfo, action: NoteAction, text: String): Boolean {
        replying = null
        if (n.demo) {
            recent.value = recent.value.filterNot { it.key == n.key }
            return true
        }
        val intent = action.intent ?: return false
        return try {
            val fill = Intent()
            val results = Bundle()
            action.inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(action.inputs.toTypedArray(), fill, results)
            intent.send(context, 0, fill)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun cancelReply() {
        replying = null
    }

    // ---- Alarm actions ----

    fun alarmAction(r: AlarmRing, action: NoteAction) {
        if (r.demo) {
            alarmRing.value = null
            return
        }
        send(action.intent)
    }

    fun openAlarm() {
        val a = alarmDemo.value ?: alarm.value ?: return
        if (!a.demo) send(a.show)
    }

    // ---- Live activity actions ----

    fun liveAction(l: LiveInfo, action: NoteAction) {
        if (l.demo) {
            live.value = null
            return
        }
        send(action.intent)
    }

    fun openNote(n: NoteInfo) {
        if (note.value?.key == n.key) note.value = null
        if (n.demo) recent.value = recent.value.filterNot { it.key == n.key }
        send(n.open)
    }

    const val MAX_RECENT = 8

    private fun clockLabel(at: Long): String =
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

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
            "note" -> {
                val n = NoteInfo(
                    key = "demo-note-$now", appName = "Messages", title = "Priya Shah",
                    text = "Running five minutes late, save me a seat", largeIcon = null, smallIcon = null,
                    open = null, postedAt = now,
                    actions = listOf(NoteAction("Reply", null, listOf(RemoteInput.Builder("demo").setLabel("Reply").build())), NoteAction("Mark as read", null)),
                    demo = true,
                )
                note.value = n
                recent.value = (listOf(n) + recent.value).take(MAX_RECENT)
            }
            "alarm" -> {
                alarmDemo.value = AlarmInfo(at = now + 7 * 3_600_000L + 12 * 60_000L, show = null, demo = true)
                alarmRing.value = AlarmRing(
                    key = "demo-alarm", title = "Alarm", text = clockLabel(now), appName = "Clock", smallIcon = null,
                    actions = listOf(NoteAction("Snooze", null), NoteAction("Stop", null)), open = null, demo = true,
                )
            }
            "download" -> live.value = LiveInfo(
                key = "demo-live", appName = "Files", title = "Holiday photos.zip", text = "Downloading · 148 MB of 240 MB",
                short = null, progress = 0.62f, smallIcon = null, accent = null, open = null,
                actions = listOf(NoteAction("Pause", null), NoteAction("Cancel", null)), updatedAt = now, demo = true,
            )
            "sound" -> {
                val next = when (sound.value?.mode) {
                    2 -> 1
                    1 -> 0
                    else -> 2
                }
                sound.value = SoundMode(next, now)
            }
            "battery" -> {
                batteryDemo.value = BatteryInfo(
                    level = 64, charging = true, plugged = 4, full = false, fullInMs = 48 * 60_000L, demo = true,
                )
                batteryFlash.value = now
            }
            "clear" -> {
                if (media.value?.demo == true) media.value = null
                if (call.value?.demo == true) call.value = null
                if (timer.value?.demo == true) timer.value = null
                if (note.value?.demo == true) note.value = null
                recent.value = recent.value.filterNot { it.demo }
                batteryDemo.value = null
                alarmDemo.value = null
                if (alarmRing.value?.demo == true) alarmRing.value = null
                if (live.value?.demo == true) live.value = null
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
    val battery: Boolean = true,
    /** Drag the island down to pull a drop of recent notifications out of it. */
    val pull: Boolean = true,
    /** Keep the time in the island at all times, beside whatever else is live. */
    val alwaysTime: Boolean = false,
    /** Keep the battery in the island at all times, beside whatever else is live. */
    val alwaysBattery: Boolean = false,
    /** Where the always-on time sits: [TIME_OUTSIDE], [TIME_INSIDE] or [TIME_SPLIT]. */
    val timeSpot: String = TIME_OUTSIDE,
    /** Switch Android's own notification pop-ups off while the island shows notifications. */
    val hidePopups: Boolean = false,
    /** The next alarm, and alarms as they ring. */
    val alarms: Boolean = true,
    /** Show the next alarm whatever the time, not just in the last 12 hours before it. */
    val alarmAlways: Boolean = false,
    /** Downloads, directions, deliveries and other things under way. */
    val live: Boolean = true,
    /** Ring, vibrate or silent, for a moment when you switch. */
    val sound: Boolean = true,
) {
    /** Whether Android's pop-ups should be off right now (with the island running). */
    val wantsPopupsHidden: Boolean get() = enabled && notifications && hidePopups

    companion object {
        /** Left of the artwork, at the far end of the island. */
        const val TIME_OUTSIDE = "outside"
        /** Right of the artwork, beside the camera. */
        const val TIME_INSIDE = "inside"
        /** Around the camera: hours on one side, minutes on the other, the camera as the colon. */
        const val TIME_SPLIT = "split"
    }
}

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
            battery = p.getBoolean("battery", true),
            pull = p.getBoolean("pull", true),
            alwaysTime = p.getBoolean("alwaysTime", false),
            alwaysBattery = p.getBoolean("alwaysBattery", false),
            timeSpot = p.getString("timeSpot", null) ?: IslandPrefs.TIME_OUTSIDE,
            hidePopups = p.getBoolean("hidePopups", false),
            alarms = p.getBoolean("alarms", true),
            alarmAlways = p.getBoolean("alarmAlways", false),
            live = p.getBoolean("live", true),
            sound = p.getBoolean("sound", true),
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
            ?.putBoolean("battery", next.battery)
            ?.putBoolean("pull", next.pull)
            ?.putBoolean("alwaysTime", next.alwaysTime)
            ?.putBoolean("alwaysBattery", next.alwaysBattery)
            ?.putString("timeSpot", next.timeSpot)
            ?.putBoolean("hidePopups", next.hidePopups)
            ?.putBoolean("alarms", next.alarms)
            ?.putBoolean("alarmAlways", next.alarmAlways)
            ?.putBoolean("live", next.live)
            ?.putBoolean("sound", next.sound)
            ?.apply()
    }
}
