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
    private val seenNotes = HashMap<String, Int>()

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
            activeNotifications?.forEach { scan(it, initial = true) }
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
        calls.remove(sbn.key)
        timers.remove(sbn.key)
        seenNotes.remove(sbn.key)
        if (IslandRepository.note.value?.key == sbn.key) IslandRepository.note.value = null
        publishCallsAndTimers()
    }

    fun dismiss(key: String) {
        try {
            cancelNotification(key)
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

        if (initial || template.contains("MediaStyle") || !isPeekWorthy(sbn)) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val signature = (title + "\u0000" + text).hashCode()
        if (seenNotes[sbn.key] == signature) return
        seenNotes[sbn.key] = signature
        if (System.currentTimeMillis() - sbn.postTime > 15_000) return

        IslandRepository.note.value = NoteInfo(
            key = sbn.key,
            appName = appName(sbn.packageName),
            title = title,
            text = text,
            largeIcon = n.getLargeIcon()?.let { iconBitmap(it) },
            smallIcon = n.smallIcon,
            open = n.contentIntent,
            postedAt = sbn.postTime,
        )
    }

    private fun isPeekWorthy(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (n.category in QUIET_CATEGORIES) return false
        return try {
            val ranking = Ranking()
            currentRanking.getRanking(sbn.key, ranking) && ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT
        } catch (_: Exception) {
            true
        }
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

    @Suppress("DEPRECATION")
    private fun pendingIntent(extras: android.os.Bundle, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(key, PendingIntent::class.java) else extras.getParcelable(key)

    private fun publishCallsAndTimers() {
        val call = calls.values.firstOrNull { it.ringing } ?: calls.values.lastOrNull()
        if (IslandRepository.call.value?.demo != true) IslandRepository.call.value = call
        val now = System.currentTimeMillis()
        val timer = timers.values.filter { it.countDown }.minByOrNull { it.base - now } ?: timers.values.lastOrNull()
        if (IslandRepository.timer.value?.demo != true) IslandRepository.timer.value = timer
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
