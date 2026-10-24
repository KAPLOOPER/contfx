package dev.continuum.fx.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds other apps' playback sessions and silences their original output, so the
 * person only hears the processed copy that [AudioEngine] plays.
 *
 * Sessions come from the audio policy service dump (needs the DUMP permission),
 * plus the effect-control broadcasts that many music players send. Apps that
 * forbid capture are never silenced, otherwise they would go quiet entirely.
 */
class SessionWatcher(private val context: Context) {

    private val myUid = Process.myUid()
    private val pm = context.packageManager
    private val lock = Any()
    private val muted = HashMap<Int, AudioEffect>()
    private val announced = ConcurrentHashMap<Int, String>()
    private val labels = ConcurrentHashMap<String, String>()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return
            if (sid <= 0 || pkg == context.packageName) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> announced[sid] = pkg
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> announced.remove(sid)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        running = true
        thread = Thread({
            while (running) {
                try {
                    poll()
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(POLL_MS)
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
        thread?.interrupt()
        thread = null
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        synchronized(lock) {
            muted.values.forEach { runCatching { it.release() } }
            muted.clear()
        }
        EngineStatus.processing.value = emptyList()
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

        val processing = LinkedHashSet<String>()
        val blocked = LinkedHashSet<String>()
        synchronized(lock) {
            if (!running) return
            val gone = muted.keys - candidates.keys
            gone.forEach { sid -> muted.remove(sid)?.let { runCatching { it.release() } } }
            for ((sid, pkg) in candidates) {
                val label = labelOf(pkg)
                if (!canBeCaptured(pkg, allowLog)) {
                    blocked += label
                    muted.remove(sid)?.let { runCatching { it.release() } }
                    continue
                }
                if (!muted.containsKey(sid)) mute(sid)?.let { muted[sid] = it }
                processing += label
            }
        }
        EngineStatus.processing.value = processing.toList()
        EngineStatus.blocked.value = blocked.toList()
    }

    private fun mute(sid: Int): AudioEffect? = try {
        DynamicsProcessing(Int.MAX_VALUE, sid, null).apply {
            setInputGainAllChannelsTo(-200f)
            setEnabled(true)
        }
    } catch (_: Throwable) {
        null
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

    private fun isCapturedUsage(usage: String): Boolean {
        val u = usage.uppercase()
        return u.contains("MEDIA") || u.contains("GAME") || u.contains("UNKNOWN") || u == "0" || u == "1" || u == "14"
    }

    private companion object {
        const val POLL_MS = 1000L

        // Android 10–12: "Session Id: 41 UID: 10123 ..." / Android 13+: "Session ID: 41; uid 10123; ..."
        val SESSION_REGEX = Regex("""Session I[dD]:\s*(\d+);?\s*(?:UID:|uid)\s*(\d+)[\s\S]*?Usage:\s*(\w+)""")
        val ALLOW_REGEX = Regex("""allowPlaybackCapture=(true|false)[^\n]*?packageName=([\w.:]+)""", RegexOption.IGNORE_CASE)
    }
}
