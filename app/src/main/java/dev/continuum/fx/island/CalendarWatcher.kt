package dev.continuum.fx.island

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * Follows the calendar for the island's upcoming event: read when it starts, whenever the
 * calendar changes (a sync, an edit), when the clock or time zone changes, and again just
 * when what the island shows would change. Nothing is read without the calendar permission
 * and the Calendar events switch on.
 */
class CalendarWatcher(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var observing = false
    @Volatile
    private var started = false

    private val readSoon = Runnable {
        try {
            io.execute { read() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Stopped in the meantime.
        }
    }

    private val observer = object : ContentObserver(main) {
        // A sync can change many rows at once: read once it settles.
        override fun onChange(selfChange: Boolean) = poke(delayMs = 1_500)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = poke()
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        poke()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(readSoon)
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        unobserve()
        io.shutdownNow()
        IslandRepository.event.value = null
    }

    /** Reads the calendar again, after [delayMs] (later calls fold into one read). */
    fun poke(delayMs: Long = 0) {
        if (!started) return
        main.removeCallbacks(readSoon)
        main.postDelayed(readSoon, delayMs)
    }

    private fun allowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun read() {
        if (!started) return
        val wanted = IslandSettings.state.value.let { it.enabled && it.events }
        if (!wanted || !allowed()) {
            main.post { unobserve() }
            IslandRepository.event.value = null
            // Access can be given later from the system's settings: look again in a while.
            if (wanted) poke(delayMs = REFRESH_MS)
            return
        }
        main.post { observe() }
        val now = System.currentTimeMillis()
        val events = try {
            query(now)
        } catch (_: Exception) {
            emptyList()
        }
        val next = Upcoming.pick(events, now)
        if (!started) return
        IslandRepository.event.value = next
        // Come back when the island's view of it changes, or in a while to look further ahead.
        val at = next?.let { Upcoming.nextChange(it, now) } ?: (now + REFRESH_MS)
        poke(delayMs = (at - now).coerceIn(1_000L, REFRESH_MS))
    }

    private fun query(now: Long): List<EventInfo> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - Upcoming.LINGER_MS)
            ContentUris.appendId(it, now + LOOK_AHEAD_MS)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.EVENT_LOCATION,
            Instances.DESCRIPTION,
            Instances.CUSTOM_APP_URI,
        )
        // Timed events on calendars you can see, that aren't cancelled or declined.
        val selection = "${Instances.ALL_DAY} = 0 AND ${Instances.VISIBLE} = 1" +
            " AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})" +
            " AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS} != ${Attendees.ATTENDEE_STATUS_DECLINED})"
        val out = ArrayList<EventInfo>()
        context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 24) {
                val location = c.getString(4)
                out += EventInfo(
                    id = c.getLong(0),
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "Event" },
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    location = location?.trim()?.takeIf { it.isNotEmpty() },
                    joinUrl = Upcoming.meetingLink(location, c.getString(6), c.getString(5)),
                )
            }
        }
        return out
    }

    private fun observe() {
        if (observing || !started) return
        observing = try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun unobserve() {
        if (!observing) return
        observing = false
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val LOOK_AHEAD_MS = 3 * 3_600_000L
        const val REFRESH_MS = 10 * 60_000L
    }
}
