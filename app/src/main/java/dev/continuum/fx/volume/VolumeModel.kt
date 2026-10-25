package dev.continuum.fx.volume

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.continuum.fx.island.IslandNotificationListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** The system's volume streams the panel shows, in the order it shows them. */
enum class VolStream(val id: Int, val label: String) {
    Media(AudioManager.STREAM_MUSIC, "Media"),
    Call(AudioManager.STREAM_VOICE_CALL, "Calls"),
    Ring(AudioManager.STREAM_RING, "Ring"),
    Notification(AudioManager.STREAM_NOTIFICATION, "Notifications"),
    Alarm(AudioManager.STREAM_ALARM, "Alarms"),
}

/** One stream's level, in the device's own steps. */
data class Level(val index: Int, val min: Int, val max: Int) {
    val steps: Int get() = (max - min).coerceAtLeast(1)
    val fraction: Float get() = ((index - min).toFloat() / steps).coerceIn(0f, 1f)
    val percent: Int get() = (fraction * 100f).roundToInt()
    val atMax: Boolean get() = index >= max
    /** The index nearest [fraction] of the way up. */
    fun indexAt(fraction: Float): Int = (min + (fraction.coerceIn(0f, 1f) * steps).roundToInt()).coerceIn(min, max)
}

data class VolumeSnapshot(
    val levels: Map<VolStream, Level> = emptyMap(),
    /** AudioManager.RINGER_MODE_*. */
    val ringer: Int = AudioManager.RINGER_MODE_NORMAL,
    /** A phone call, or an app's voice or video call. */
    val inCall: Boolean = false,
) {
    operator fun get(s: VolStream): Level = levels[s] ?: Level(0, 0, 1)

    /** What the volume keys move right now. */
    val keyStream: VolStream get() = if (inCall) VolStream.Call else VolStream.Media
}

/** What a volume step did. */
enum class StepResult { Moved, AtEnd, SystemAsked }

/**
 * The phone's volumes, kept current: every stream's level, the ringer and whether a call is on.
 * Changes are heard from Android's own volume broadcasts, so a level moved anywhere (another app,
 * a Bluetooth device, the system) shows straight away.
 */
object VolumeModel {
    private val _state = MutableStateFlow(VolumeSnapshot())
    val state: StateFlow<VolumeSnapshot> = _state.asStateFlow()

    /** Why the last change didn't happen, for a moment on screen. */
    val problem = MutableStateFlow<String?>(null)

    private var appContext: Context? = null
    private var am: AudioManager? = null
    private val main = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    /** Starts listening, once for the life of the app. */
    fun start(context: Context) {
        if (appContext != null) {
            refresh()
            return
        }
        val ctx = context.applicationContext
        appContext = ctx
        am = ctx.getSystemService(AudioManager::class.java)
        val filter = IntentFilter().apply {
            addAction(VOLUME_CHANGED)
            addAction(MUTE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                am?.addOnModeChangedListener(ContextCompat.getMainExecutor(ctx)) { refresh() }
            } catch (_: Exception) {
            }
        }
        refresh()
    }

    fun refresh() {
        val a = am ?: return
        val levels = VolStream.entries.associateWith { s ->
            try {
                Level(a.getStreamVolume(s.id), a.getStreamMinVolume(s.id), a.getStreamMaxVolume(s.id))
            } catch (_: Exception) {
                Level(0, 0, 1)
            }
        }
        val next = VolumeSnapshot(levels = levels, ringer = ringerOf(a), inCall = inCall(a))
        if (next != _state.value) _state.value = next
    }

    /**
     * One step up ([dir] > 0) or down. Android's hearing protection can refuse a step up on
     * headphones until you confirm; then its own warning is let through to ask.
     */
    fun step(stream: VolStream, dir: Int): StepResult {
        val a = am ?: return StepResult.AtEnd
        val before = a.getStreamVolume(stream.id)
        try {
            a.adjustStreamVolume(stream.id, if (dir > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        } catch (e: SecurityException) {
            problem.value = dndProblem(stream)
            return StepResult.AtEnd
        }
        val after = a.getStreamVolume(stream.id)
        refresh()
        if (after != before) return StepResult.Moved
        val max = a.getStreamMaxVolume(stream.id)
        if (dir > 0 && before < max && stream == VolStream.Media) {
            a.adjustStreamVolume(stream.id, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return StepResult.SystemAsked
        }
        return StepResult.AtEnd
    }

    /** Sets [stream] to [index] of its own steps. False when Android refused. */
    fun set(stream: VolStream, index: Int): Boolean {
        val a = am ?: return false
        return try {
            if (a.getStreamVolume(stream.id) != index) a.setStreamVolume(stream.id, index, 0)
            refresh()
            true
        } catch (e: SecurityException) {
            problem.value = dndProblem(stream)
            false
        }
    }

    /**
     * Ring, vibrate or silent. Silent goes through the privileged helper when it's there, the
     * way Android's own panel does it, which leaves Do Not Disturb alone; an ordinary app's
     * request for silent switches Do Not Disturb on with it on recent Android.
     */
    fun setRinger(mode: Int) {
        val a = am ?: return
        fun direct() {
            try {
                a.ringerMode = mode
            } catch (e: SecurityException) {
                problem.value = "Silent needs Do Not Disturb access, or Shizuku for the helper."
            }
            main.postDelayed({ refresh() }, 120)
        }
        if (mode == AudioManager.RINGER_MODE_SILENT && VolumeHelper.ready.value) {
            VolumeHelper.setRingerMode(mode) { ok -> main.post { if (ok) refresh() else direct() } }
        } else {
            direct()
        }
    }

    private fun dndProblem(stream: VolStream) =
        "Do Not Disturb is on, so ${stream.label.lowercase()} can't change without Do Not Disturb access."

    private fun ringerOf(a: AudioManager): Int = try {
        a.ringerMode
    } catch (_: Exception) {
        AudioManager.RINGER_MODE_NORMAL
    }

    private fun inCall(a: AudioManager): Boolean = try {
        a.mode == AudioManager.MODE_IN_CALL || a.mode == AudioManager.MODE_IN_COMMUNICATION
    } catch (_: Exception) {
        false
    }

    /** A call ringing in: the volume keys are Android's then, to silence it. */
    fun ringing(): Boolean = try {
        am?.mode == AudioManager.MODE_RINGTONE
    } catch (_: Exception) {
        false
    }

    /**
     * Something playing on another device (a Chromecast, a speaker group): Android sends the
     * volume keys to that device, so they're left to it.
     */
    fun castingRemotely(context: Context): Boolean = try {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, IslandNotificationListener::class.java)
        msm.getActiveSessions(listener).any { c: MediaController ->
            c.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE &&
                c.playbackState?.state == PlaybackState.STATE_PLAYING
        }
    } catch (_: Exception) {
        // Without notification access the sessions can't be seen; assume nothing is cast.
        false
    }

    private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    private const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"
}
