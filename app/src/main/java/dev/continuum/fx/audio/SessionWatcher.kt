package dev.continuum.fx.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.os.Handler
import android.os.Looper
import android.media.audiofx.DynamicsProcessing
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/** What the watcher puts on each app's sound. */
interface SessionEffects {
    /** An effect on session [sid], which [pkg] is playing, or null when it can't be had. */
    fun attach(sid: Int, pkg: String): AudioEffect?

    /** Whether apps that forbid capture are left alone (true when capturing). */
    val respectsCaptureBlock: Boolean

    /** Called after each check, with every effect in place, on the watcher's thread. */
    fun synced(effects: Collection<AudioEffect>) = Unit
}

/** Capture mode: each app's own sound is silenced, so only the processed copy is heard. */
object MuteEffects : SessionEffects {
    override val respectsCaptureBlock = true

    override fun attach(sid: Int, pkg: String): AudioEffect? {
        val effect = try {
            DynamicsProcessing(Int.MAX_VALUE, sid, null)
        } catch (_: Exception) {
            return null
        }
        // Only counted once it's really on; otherwise let go of it, to be tried again.
        return try {
            effect.setInputGainAllChannelsTo(-200f)
            check(effect.setEnabled(true) == AudioEffect.SUCCESS)
            effect
        } catch (_: Exception) {
            runCatching { effect.release() }
            null
        }
    }
}

/**
 * Finds other apps' playback sessions and puts [effects] on each: in capture mode, silencing
 * the original (the person hears the processed copy [AudioEngine] plays); in Direct mode, the
 * EQ itself ([DirectEq]).
 *
 * Sessions come from the audio policy service dump (needs the DUMP permission),
 * plus the effect-control broadcasts that many music players send. When capturing, apps that
 * forbid capture are never silenced, otherwise they would go quiet entirely.
 *
 * Android says the moment any app starts or stops playing, so a new session is found
 * straight away; a slower regular check catches anything else.
 */
class SessionWatcher(private val context: Context, private val effects: SessionEffects = MuteEffects) {

    private val myUid = Process.myUid()
    private val pm = context.packageManager
    private val lock = Any()
    private val sessions = SessionRegistry<AudioEffect> { it.release() }
    private val announced = ConcurrentHashMap<Int, String>()
    private val labels = ConcurrentHashMap<String, String>()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private val wake = Object()
    @Volatile
    private var nudged = false
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val playback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = nudge()
    }

    /** Checks again now rather than at the next regular check. */
    private fun nudge() {
        synchronized(wake) {
            nudged = true
            wake.notifyAll()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
            if (sid <= 0 || pkg == context.packageName) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> announced[sid] = pkg
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> announced.remove(sid)
            }
            nudge()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        try {
            audio?.registerAudioPlaybackCallback(playback, main)
        } catch (_: Exception) {
        }
        running = true
        thread = Thread({
            while (running) {
                try {
                    poll()
                } catch (_: Throwable) {
                }
                try {
                    synchronized(wake) {
                        if (!nudged) wake.wait(POLL_MS)
                        nudged = false
                    }
                    // Let a burst of changes (a player opening several streams) settle first.
                    Thread.sleep(SETTLE_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "ContinuumSessions").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            audio?.unregisterAudioPlaybackCallback(playback)
        } catch (_: Exception) {
        }
        thread?.interrupt()
        thread = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        synchronized(lock) {
            sessions.clear()
        }
        EngineStatus.processing.value = emptyList()
        EngineStatus.processingPackages.value = emptyList()
        EngineStatus.blocked.value = emptyList()
    }

    private fun poll() {
        val dump = ServiceDump.read("media.audio_policy")
        val candidates = HashMap<Int, String>()
        val allowLog = HashMap<String, Boolean>()

        if (dump != null) {
            ALLOW_REGEX.findAll(dump).forEach {
                allowLog[it.groupValues[2].removePrefix("shared:")] = it.groupValues[1].equals("true", ignoreCase = true)
            }
            SESSION_REGEX.findAll(dump).forEach { m ->
                val sid = m.groupValues[1].toIntOrNull() ?: return@forEach
                val uid = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (sid <= 0 || uid == myUid || uid < Process.FIRST_APPLICATION_UID) return@forEach
                if (!isCapturedUsage(m.groupValues[3])) return@forEach
                val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return@forEach
                candidates[sid] = pkg
            }
        }
        announced.forEach { (sid, pkg) -> candidates.putIfAbsent(sid, pkg) }

        val wanted = HashMap<Int, String>()
        val blocked = LinkedHashSet<String>()
        for ((sid, pkg) in candidates) {
            if (effects.respectsCaptureBlock && !canBeCaptured(pkg, allowLog)) blocked += labelOf(pkg)
            else wanted[sid] = pkg
        }
        val packages: List<String>
        synchronized(lock) {
            if (!running) return
            // Only apps whose effect is really in place count as processed.
            packages = sessions.sync(wanted, effects::attach).values.distinct()
            try {
                effects.synced(sessions.effects())
            } catch (_: Throwable) {
            }
        }
        EngineStatus.processing.value = packages.map(::labelOf)
        if (packages != EngineStatus.processingPackages.value) EngineStatus.processingPackages.value = packages
        HeardApps.add(packages)
        EngineStatus.blocked.value = blocked.toList()
    }

    private fun canBeCaptured(pkg: String, allowLog: Map<String, Boolean>): Boolean {
        allowLog[pkg]?.let { return it }
        // Apps built for Android 9 or older opt out of capture by default.
        return try {
            pm.getApplicationInfo(pkg, 0).targetSdkVersion >= 29
        } catch (_: Exception) {
            false
        }
    }

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /** Every effect in place now, for [SessionEffects] to bring up to date between checks. */
    fun attached(): List<AudioEffect> = synchronized(lock) { sessions.effects() }

    private fun isCapturedUsage(usage: String): Boolean {
        val u = usage.uppercase()
        return u.contains("MEDIA") || u.contains("GAME") || u.contains("UNKNOWN") || u == "0" || u == "1" || u == "14"
    }

    private companion object {
        /** The regular check, for anything Android didn't announce. */
        const val POLL_MS = 2500L
        const val SETTLE_MS = 60L

        // Android 10–12: "Session Id: 41 UID: 10123 ..." / Android 13+: "Session ID: 41; uid 10123; ..."
        val SESSION_REGEX = Regex("""Session I[dD]:\s*(\d+);?\s*(?:UID:|uid)\s*(\d+)[\s\S]*?Usage:\s*(\w+)""")
        val ALLOW_REGEX = Regex("""allowPlaybackCapture=(true|false)[^\n]*?packageName=([\w.:]+)""", RegexOption.IGNORE_CASE)
    }
}
