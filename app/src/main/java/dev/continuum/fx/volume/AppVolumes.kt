/*
 * The session logic (a level kept while the app's process lives and re-applied to every new
 * player it opens) is adapted from Volume++'s AppVolumeController by noel-digital-fan
 * (https://github.com/noel-digital-fan/volume_plus_plus), MIT License. See THIRD_PARTY_NOTICES.md.
 */
package dev.continuum.fx.volume

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** An app making sound, with the volume it's been given (0..1). */
data class AppAudio(val pkg: String, val label: String, val volume: Float, val piids: List<Int>)

/**
 * Each app's own volume, through the helper. Android gives every player a fresh id whenever
 * an app pauses and resumes (the screen going off is enough), at full volume, so a level is
 * kept per app for as long as its process is alive and pushed to every new player it opens.
 * With remembering on it's also stored, so the app starts at that level next time; setting an
 * app back to full forgets it.
 *
 * Everything here runs on one thread of its own, so nothing needs a lock.
 */
object AppVolumes {
    private val _playing = MutableStateFlow<List<AppAudio>>(emptyList())
    /** Apps making sound now, for the sheet and the app's Volume page. */
    val playing: StateFlow<List<AppAudio>> = _playing.asStateFlow()

    /** Apps that have made sound, for the play-alongside list (newest first). */
    private val _known = MutableStateFlow<List<String>>(emptyList())
    val known: StateFlow<List<String>> = _known.asStateFlow()

    /** Apps that play alongside others: they don't pause anything, and nothing pauses them. */
    private val _alongside = MutableStateFlow<Set<String>>(emptySet())
    val alongside: StateFlow<Set<String>> = _alongside.asStateFlow()

    /** Why playing alongside isn't working right now, if it isn't. */
    val alongsideProblem = MutableStateFlow<String?>(null)

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "ContinuumAppVolumes") }
    private val scope = CoroutineScope(SupervisorJob() + thread.asCoroutineDispatcher())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val main = Handler(Looper.getMainLooper())

    private class Session(var volume: Float) {
        /** piid → the volume last pushed to it, so it's only written on a change. */
        val applied = HashMap<Int, Float>()
    }

    private val sessions = LinkedHashMap<String, Session>()
    private val labels = HashMap<String, String>()
    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var am: AudioManager? = null

    fun start(context: Context) {
        if (appContext != null) {
            poke()
            return
        }
        val ctx = context.applicationContext
        appContext = ctx
        prefs = ctx.getSharedPreferences("app_volumes", Context.MODE_PRIVATE)
        am = ctx.getSystemService(AudioManager::class.java)
        _known.value = prefs?.getString(KNOWN, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        _alongside.value = prefs?.getString(ALONGSIDE, null)?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
        try {
            am?.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = poke()
            }, main)
        } catch (_: Exception) {
        }
        scope.launch {
            VolumeHelper.ready.collect { ready ->
                poke()
                if (ready) {
                    undoOldAlongside()
                    pushAlongside()
                }
            }
        }
        scope.launch { loop() }
    }

    /** Switches [pkg] playing alongside on or off, and hands the list to the helper. */
    fun setAlongside(pkg: String, on: Boolean) {
        val next = if (on) _alongside.value + pkg else _alongside.value - pkg
        _alongside.value = next
        prefs?.edit()?.putString(ALONGSIDE, next.joinToString(","))?.apply()
        pushAlongside()
    }

    private fun pushAlongside() {
        if (!VolumeHelper.ready.value) return
        val set = _alongside.value
        VolumeHelper.setAlongside(set) { problem -> alongsideProblem.value = if (set.isEmpty()) null else problem }
    }

    /**
     * Earlier builds made an app play alongside by refusing it audio focus, which stops most
     * players playing at all. Put back any refusal left on an app this app has seen, once.
     */
    private fun undoOldAlongside() {
        val p = prefs ?: return
        if (p.getBoolean(UNDONE_OLD, false)) return
        val known = _known.value.toSet() + _alongside.value
        val refused = VolumeHelper.playsAlongside().filter { it in known }
        for (pkg in refused) VolumeHelper.setPlaysAlongside(pkg, false) {}
        p.edit().putBoolean(UNDONE_OLD, true).apply()
    }

    /** Look again soon: something started, stopped, or the helper came back. */
    fun poke() {
        wake.trySend(Unit)
    }

    /** The level [pkg] plays at (its session's, else the stored one, else full). */
    fun volumeFor(pkg: String): Float = sessions[pkg]?.volume ?: stored(pkg)

    /** Sets [pkg]'s volume and applies it straight away to everything it's playing. */
    fun setVolume(pkg: String, level: Float) {
        val v = level.coerceIn(0f, 1f)
        // Shown at once, so the slider never waits on the helper.
        _playing.value = _playing.value.map { if (it.pkg == pkg) it.copy(volume = v) else it }
        scope.launch {
            if (VolumeSettings.state.value.rememberApps) store(pkg, v)
            val session = sessions.getOrPut(pkg) { Session(v) }
            session.volume = v
            session.applied.clear()
            for (piid in _playing.value.firstOrNull { it.pkg == pkg }?.piids.orEmpty()) {
                if (VolumeHelper.setPlayerVolume(piid, v)) session.applied[piid] = v
            }
        }
    }

    fun label(pkg: String): String = synchronized(labels) {
        labels.getOrPut(pkg) {
            val pm = appContext?.packageManager ?: return@synchronized pkg
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Exception) {
                pkg
            }
        }
    }

    private val icons = HashMap<String, Bitmap?>()

    /** A small copy of [pkg]'s icon, drawn once and kept. */
    fun icon(pkg: String, sizePx: Int): Bitmap? = synchronized(icons) {
        icons.getOrPut("$pkg@$sizePx") {
            try {
                val d = appContext?.packageManager?.getApplicationIcon(pkg) ?: return@getOrPut null
                Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { b ->
                    d.setBounds(0, 0, sizePx, sizePx)
                    d.draw(Canvas(b))
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun loop() {
        while (true) {
            val next = try {
                sync()
            } catch (_: Exception) {
                POLL_DORMANT_MS
            }
            if (next == null) wake.receive() else withTimeoutOrNull(next) { wake.receive() }
        }
    }

    /** One pass: what's playing, levels re-applied, gone apps forgotten. The wait until the next. */
    private fun sync(): Long? {
        if (!VolumeHelper.ready.value) {
            if (_playing.value.isNotEmpty()) _playing.value = emptyList()
            return null
        }
        val a = am
        val audible = a != null && (a.isMusicActive || a.mode == AudioManager.MODE_IN_COMMUNICATION || a.mode == AudioManager.MODE_IN_CALL)
        val own = appContext?.packageName
        val players = if (audible) VolumeHelper.players().filter { it.pkg != own } else emptyList()
        val playing = players.groupBy { it.pkg }.mapValues { (_, ps) -> ps.map { it.piid } }

        for ((pkg, piids) in playing) {
            val session = sessions.getOrPut(pkg) { Session(stored(pkg)) }
            session.applied.keys.retainAll(piids.toHashSet())
            // Full is Android's own default: only a lowered level needs keeping up.
            if (session.volume < FULL) {
                for (piid in piids) {
                    if (session.applied[piid] == session.volume) continue
                    if (VolumeHelper.setPlayerVolume(piid, session.volume)) session.applied[piid] = session.volume
                }
            }
            remember(pkg)
        }
        // Paused but alive keeps its level for when it resumes; closed starts again from scratch.
        for (pkg in sessions.keys.filter { it !in playing }) {
            if (!VolumeHelper.isPackageRunning(pkg)) sessions.remove(pkg) else sessions[pkg]?.applied?.clear()
        }
        if (sessions.size > MAX_SESSIONS) {
            val it = sessions.entries.iterator()
            while (sessions.size > MAX_SESSIONS && it.hasNext()) if (it.next().key !in playing) it.remove()
        }
        val list = playing.map { (pkg, piids) -> AppAudio(pkg, label(pkg), sessions[pkg]?.volume ?: FULL, piids) }
        if (list != _playing.value) _playing.value = list
        return when {
            playing.isNotEmpty() -> POLL_ACTIVE_MS
            sessions.isNotEmpty() -> POLL_DORMANT_MS
            else -> null
        }
    }

    private fun stored(pkg: String): Float =
        if (!VolumeSettings.state.value.rememberApps) FULL else prefs?.getFloat(pkg, FULL) ?: FULL

    private fun store(pkg: String, v: Float) {
        val p = prefs ?: return
        // Full is where every app starts anyway, so it's forgotten rather than stored.
        if (v >= FULL - 0.005f) p.edit().remove(pkg).apply() else p.edit().putFloat(pkg, (v * 100f).roundToInt() / 100f).apply()
    }

    /** Adds [pkg] to the apps that have made sound, newest first, a few dozen at most. */
    private fun remember(pkg: String) {
        val list = _known.value
        if (list.firstOrNull() == pkg) return
        val next = (listOf(pkg) + list.filter { it != pkg }).take(MAX_KNOWN)
        _known.value = next
        prefs?.edit()?.putString(KNOWN, next.joinToString(","))?.apply()
    }

    private const val FULL = 1f
    private const val POLL_ACTIVE_MS = 1_500L
    private const val POLL_DORMANT_MS = 4_000L
    private const val MAX_SESSIONS = 12
    private const val MAX_KNOWN = 40
    private const val KNOWN = "_known"
    private const val ALONGSIDE = "_alongside"
    private const val UNDONE_OLD = "_undone_focus_refusals"
}
