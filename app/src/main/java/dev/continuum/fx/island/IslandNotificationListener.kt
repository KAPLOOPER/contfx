package dev.continuum.fx.island

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.max

/**
 * Feeds the island: the active media session, calls, running timers and fresh
 * notifications. Needs notification access, granted in settings or with Shizuku.
 */
class IslandNotificationListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val timers = LinkedHashMap<String, TimerInfo>()
    private val calls = LinkedHashMap<String, CallInfo>()
    private val alarmRings = LinkedHashMap<String, AlarmRing>()
    private val lives = LinkedHashMap<String, LiveInfo>()
    /** Full-screen alerts already opened, so updates don't open them again. */
    private val openedFullScreen = HashSet<String>()
    private val seenNotes = HashMap<String, Int>()
    private val recent = HashMap<String, NoteInfo>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pickController(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshSessions()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMedia()

        override fun onSessionDestroyed() = refreshSessions()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandRepository.listener = this
        IslandRepository.listenerActive.value = true
        try {
            val manager = getSystemService(MediaSessionManager::class.java)
            sessions = manager
            val component = ComponentName(this, IslandNotificationListener::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            pickController(manager.getActiveSessions(component))
        } catch (_: Exception) {
        }
        try {
            activeNotifications?.sortedBy { it.postTime }?.forEach { scan(it, initial = true) }
        } catch (_: Exception) {
        }
        publishCallsAndTimers()
    }

    override fun onListenerDisconnected() {
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        IslandRepository.mediaController = null
        if (IslandRepository.listener === this) IslandRepository.listener = null
        IslandRepository.listenerActive.value = false
        if (IslandRepository.media.value?.demo != true) IslandRepository.media.value = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        scan(sbn, initial = false)
        publishCallsAndTimers()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        openedFullScreen.remove(sbn.key)
        alarmRings.remove(sbn.key)
        lives.remove(sbn.key)
        calls.remove(sbn.key)
        timers.remove(sbn.key)
        seenNotes.remove(sbn.key)
        if (recent.remove(sbn.key) != null) publishRecent()
        if (IslandRepository.note.value?.key == sbn.key) IslandRepository.note.value = null
        publishCallsAndTimers()
    }

    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (_: Exception) {
        }
    }

    /** Clears everything clearable, like the shade's Clear all. */
    fun clearAll() {
        try {
            cancelAllNotifications()
        } catch (_: Exception) {
        }
    }

    // ---- Media ----

    private fun refreshSessions() {
        val manager = sessions ?: return
        try {
            pickController(manager.getActiveSessions(ComponentName(this, IslandNotificationListener::class.java)))
        } catch (_: Exception) {
        }
    }

    private fun pickController(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = best
            best?.registerCallback(controllerCallback, main)
            IslandRepository.mediaController = best
        }
        publishMedia()
    }

    private fun publishMedia() {
        val c = controller
        if (c == null) {
            if (IslandRepository.media.value?.demo != true) IslandRepository.media.value = null
            return
        }
        val state = c.playbackState
        val meta = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (meta == null || title.isNullOrBlank()) {
            if (IslandRepository.media.value?.demo != true) IslandRepository.media.value = null
            return
        }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val previous = IslandRepository.media.value
        val accent = if (previous != null && previous.art === art) previous.accent else accentOf(art)
        IslandRepository.media.value = MediaInfo(
            title = title,
            artist = artist,
            art = art,
            accent = accent,
            playing = state?.state == PlaybackState.STATE_PLAYING,
            positionMs = state?.position ?: 0L,
            durationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION),
            sampledAt = state?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            packageName = c.packageName,
        )
    }

    // ---- Notifications: calls, timers and peeks ----

    private fun scan(sbn: StatusBarNotification, initial: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val extras = n.extras
        val prefs = IslandSettings.state.value

        // An alarm, or a finished timer, ringing now: the island shows it with its buttons.
        val ring = parseAlarmRing(sbn)
        if (ring != null) alarmRings[sbn.key] = ring else alarmRings.remove(sbn.key)

        // With Android's pop-ups off, alerts that would have popped up over what you're doing
        // (alarms or calls the island isn't showing) open full screen instead.
        val fullScreen = n.fullScreenIntent
        if (!initial && fullScreen != null && sbn.key !in openedFullScreen && PopupGuard.hiding(this)) {
            val islandRings = (n.category == Notification.CATEGORY_CALL && prefs.calls) || (ring != null && prefs.alarms)
            if (!islandRings) {
                openedFullScreen += sbn.key
                PopupGuard.openFullScreen(this, fullScreen)
            }
        }
        if (ring != null) return

        if (n.category == Notification.CATEGORY_CALL) {
            parseCall(sbn)?.let { calls[sbn.key] = it } ?: calls.remove(sbn.key)
            return
        }

        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        if (chronometer && sbn.isOngoing && !template.contains("MediaStyle")) {
            timers[sbn.key] = TimerInfo(
                key = sbn.key,
                label = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: "Timer",
                base = n.`when`,
                countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
                open = n.contentIntent,
                actions = n.actions.orEmpty().take(2).map { IslandAction(it.title?.toString().orEmpty(), it.actionIntent) },
                appName = appName(sbn.packageName),
            )
            return
        } else {
            timers.remove(sbn.key)
        }

        val live = parseLive(sbn, template)
        if (live != null) {
            lives[sbn.key] = live
            return
        } else {
            lives.remove(sbn.key)
        }

        val importance = importanceOf(sbn)
        if (template.contains("MediaStyle") || !isListable(sbn, importance)) {
            if (recent.remove(sbn.key) != null) publishRecent()
            return
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val signature = (title + "\u0000" + text).hashCode()
        val changed = seenNotes[sbn.key] != signature
        if (!changed && recent.containsKey(sbn.key)) return
        seenNotes[sbn.key] = signature

        val info = NoteInfo(
            key = sbn.key,
            appName = appName(sbn.packageName),
            title = title,
            text = text,
            largeIcon = n.getLargeIcon()?.let { iconBitmap(it) },
            smallIcon = n.smallIcon,
            open = n.contentIntent,
            postedAt = sbn.postTime,
            actions = actionsOf(n),
            clearable = sbn.isClearable,
        )
        recent[sbn.key] = info
        while (recent.size > IslandRepository.MAX_RECENT * 2) {
            recent.values.minByOrNull { it.postedAt }?.let { recent.remove(it.key) } ?: break
        }
        publishRecent()

        if (initial || !changed || importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (System.currentTimeMillis() - sbn.postTime > 15_000) return
        IslandRepository.note.value = info
    }

    private fun publishRecent() {
        val demos = IslandRepository.recent.value.filter { it.demo }
        IslandRepository.recent.value = (recent.values + demos)
            .sortedByDescending { it.postedAt }
            .take(IslandRepository.MAX_RECENT)
    }

    private fun importanceOf(sbn: StatusBarNotification): Int = try {
        val ranking = Ranking()
        if (currentRanking.getRanking(sbn.key, ranking)) ranking.importance else NotificationManager.IMPORTANCE_DEFAULT
    } catch (_: Exception) {
        NotificationManager.IMPORTANCE_DEFAULT
    }

    /** Worth keeping for the pull-down drop: a real message, not a status or progress item. */
    private fun isListable(sbn: StatusBarNotification, importance: Int): Boolean {
        val n = sbn.notification
        if (sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category in QUIET_CATEGORIES) return false
        return importance > NotificationManager.IMPORTANCE_MIN
    }

    @Suppress("DEPRECATION")
    private fun parseCall(sbn: StatusBarNotification): CallInfo? {
        val n = sbn.notification
        val extras = n.extras
        val type = extras.getInt(EXTRA_CALL_TYPE, 0)
        if (type == 0 && !sbn.isOngoing && n.fullScreenIntent == null) return null

        val person: Person? = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(EXTRA_CALL_PERSON, Person::class.java)
        } else {
            extras.getParcelable(EXTRA_CALL_PERSON)
        }
        val caller = person?.name?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: "Call"
        val photo = person?.icon?.let { iconBitmap(it) } ?: n.getLargeIcon()?.let { iconBitmap(it) }

        fun actionFor(words: List<String>): PendingIntent? = n.actions.orEmpty().firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            a.remoteInputs.isNullOrEmpty() && words.any { w -> t.contains(w) }
        }?.actionIntent

        val answer = pendingIntent(extras, EXTRA_ANSWER_INTENT) ?: actionFor(ANSWER_WORDS)
        val decline = pendingIntent(extras, EXTRA_DECLINE_INTENT) ?: actionFor(DECLINE_WORDS)
        val hangUp = pendingIntent(extras, EXTRA_HANG_UP_INTENT) ?: actionFor(HANG_UP_WORDS)
        val ringing = when (type) {
            CALL_TYPE_INCOMING, CALL_TYPE_SCREENING -> true
            CALL_TYPE_ONGOING -> false
            else -> answer != null
        }
        val started = if (!ringing && n.`when` > 0L && n.`when` <= System.currentTimeMillis()) n.`when` else null
        return CallInfo(
            key = sbn.key,
            caller = caller,
            photo = photo,
            ringing = ringing,
            startedAt = started,
            answer = answer,
            end = if (ringing) decline ?: hangUp else hangUp ?: decline,
            open = n.contentIntent,
            appName = appName(sbn.packageName),
        )
    }

    /** A notification's buttons, Reply first, leaving out the system's suggested replies. */
    private fun actionsOf(n: Notification): List<NoteAction> = n.actions.orEmpty().mapNotNull { a ->
        if (Build.VERSION.SDK_INT >= 29 && a.isContextual) return@mapNotNull null
        val title = a.title?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val inputs = a.remoteInputs.orEmpty().filter { it.allowFreeFormInput }
        NoteAction(title, a.actionIntent, inputs)
    }.sortedByDescending { it.isReply }.take(3)

    /** A ringing alarm or finished timer: an alarm that wants the whole screen. */
    private fun parseAlarmRing(sbn: StatusBarNotification): AlarmRing? {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_ALARM || n.fullScreenIntent == null) return null
        val extras = n.extras
        return AlarmRing(
            key = sbn.key,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: "Alarm",
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            appName = appName(sbn.packageName),
            smallIcon = n.smallIcon,
            actions = actionsOf(n).filter { !it.isReply }.take(2),
            open = n.contentIntent ?: n.fullScreenIntent,
        )
    }

    /**
     * Something under way that belongs in the island: a progress bar (downloads, uploads,
     * installs), directions, or one of Android's live updates (deliveries, rides).
     */
    private fun parseLive(sbn: StatusBarNotification, template: String): LiveInfo? {
        val n = sbn.notification
        if (sbn.packageName in SYSTEM_PACKAGES || template.contains("MediaStyle")) return null
        val extras = n.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val promoted = (n.flags and FLAG_PROMOTED_ONGOING) != 0
        val progressStyle = template.contains("ProgressStyle")
        val directions = n.category == Notification.CATEGORY_NAVIGATION
        val bar = max > 0 || indeterminate
        if (!sbn.isOngoing && !promoted) return null
        if (!bar && !directions && !promoted && !progressStyle) return null
        val progress = when {
            max > 0 -> (extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
            indeterminate -> -1f
            else -> null
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank() && progress == null) return null
        return LiveInfo(
            key = sbn.key,
            appName = appName(sbn.packageName),
            title = title,
            text = text,
            short = extras.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString()?.takeIf { it.isNotBlank() },
            progress = progress,
            smallIcon = n.smallIcon,
            accent = n.color.takeIf { it != 0 && it != Notification.COLOR_DEFAULT },
            open = n.contentIntent,
            actions = actionsOf(n).filter { !it.isReply }.take(2),
            updatedAt = sbn.postTime,
        )
    }

    @Suppress("DEPRECATION")
    private fun pendingIntent(extras: android.os.Bundle, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(key, PendingIntent::class.java) else extras.getParcelable(key)

    private fun publishCallsAndTimers() {
        val call = calls.values.firstOrNull { it.ringing } ?: calls.values.lastOrNull()
        if (IslandRepository.call.value?.demo != true) IslandRepository.call.value = call
        val now = System.currentTimeMillis()
        val timer = timers.values.filter { it.countDown }.minByOrNull { it.base - now } ?: timers.values.lastOrNull()
        if (IslandRepository.timer.value?.demo != true) IslandRepository.timer.value = timer
        if (IslandRepository.alarmRing.value?.demo != true) IslandRepository.alarmRing.value = alarmRings.values.lastOrNull()
        if (IslandRepository.live.value?.demo != true) IslandRepository.live.value = lives.values.maxByOrNull { it.updatedAt }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun iconBitmap(icon: Icon): Bitmap? = try {
        icon.loadDrawable(this)?.toBitmap(144, 144)
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_CALL_PERSON = "android.callPerson"
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        private const val CALL_TYPE_INCOMING = 1
        private const val CALL_TYPE_ONGOING = 2
        private const val CALL_TYPE_SCREENING = 3
        /** Notification.FLAG_PROMOTED_ONGOING (Android 16): one of Android's live updates. */
        private const val FLAG_PROMOTED_ONGOING = 0x00040000
        /** Notification.EXTRA_SHORT_CRITICAL_TEXT (Android 16): a few characters for a status chip. */
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
        private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui")

        private val ANSWER_WORDS = listOf("answer", "accept", "pick up")
        private val DECLINE_WORDS = listOf("decline", "reject")
        private val HANG_UP_WORDS = listOf("hang up", "end call", "hangup", "end")

        private val QUIET_CATEGORIES = setOf(
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STATUS,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_CALL,
        )

        fun component(context: Context) = ComponentName(context, IslandNotificationListener::class.java)

        /** Average colour of the artwork, nudged bright enough to read on black ink. */
        fun accentOf(bitmap: Bitmap?): Int {
            if (bitmap == null) return 0xFFF4F4F1.toInt()
            return try {
                val small = Bitmap.createScaledBitmap(bitmap, 12, 12, true)
                var r = 0L
                var g = 0L
                var b = 0L
                for (x in 0 until 12) for (y in 0 until 12) {
                    val c = small.getPixel(x, y)
                    r += Color.red(c)
                    g += Color.green(c)
                    b += Color.blue(c)
                }
                val hsv = FloatArray(3)
                Color.RGBToHSV((r / 144).toInt(), (g / 144).toInt(), (b / 144).toInt(), hsv)
                hsv[1] = (hsv[1] * 1.25f).coerceAtMost(1f)
                hsv[2] = max(hsv[2], 0.72f)
                Color.HSVToColor(hsv)
            } catch (_: Exception) {
                0xFFF4F4F1.toInt()
            }
        }
    }
}
