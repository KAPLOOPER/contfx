/*
 * Adapted from Volume++'s UserService by noel-digital-fan
 * (https://github.com/noel-digital-fan/volume_plus_plus), MIT License. See THIRD_PARTY_NOTICES.md.
 */
package dev.continuum.fx.volume

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.LoudnessEnhancer
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs in a process Shizuku starts as the shell user, which may do what an app can't: see every
 * player and set each one's own volume (hidden AudioPlaybackConfiguration APIs, open to the
 * shell), set the ringer through Android's internal path, change the audio-focus app-op and
 * attach a loudness effect to the whole output.
 *
 * Shizuku creates it through either constructor, so both exist (and R8 keeps them).
 */
class VolumeHelperService() : IVolumeHelper.Stub() {

    private var context: Context? = null

    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    private var enhancer: LoudnessEnhancer? = null

    // ---- Each player's own volume, and ducking on top of it. ----
    private val lock = Any()
    /** The volume the app gave each player (piid), so a duck can be lifted back to it. */
    private val levels = HashMap<Int, Float>()
    private val duckedUids = HashSet<Int>()
    private var watching: AudioManager.AudioPlaybackCallback? = null
    /** Its own thread, rather than relying on how the process that hosts the helper loops. */
    private val callbacks by lazy { android.os.HandlerThread("ContinuumDuck").apply { start() } }
    private var focus: FocusPolicy? = null

    override fun destroy() {
        // The boost lives in the audio system, so it goes before we do; focus goes back to Android.
        releaseEnhancer()
        focus?.shutdown()
        exitProcess(0)
    }

    override fun setAlongside(packages: List<String>): String {
        val ctx = context ?: return "ERROR: no context"
        val set = packages.filter { PACKAGE.matches(it) }.toSet()
        if (set.isEmpty() && focus == null) return ""
        val f = focus ?: FocusPolicy(ctx) { uid, on -> duck(uid, on) }.also { focus = it }
        return f.setAlongside(set)
    }

    /** Lowers everything [uid] plays to a fifth (Android's own duck level), or puts it back. */
    private fun duck(uid: Int, on: Boolean) {
        val ctx = context ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        synchronized(lock) {
            if (on) duckedUids += uid else duckedUids -= uid
            for (cfg in am.activePlaybackConfigurations) {
                if (cfg.invokeInt("getClientUid") != uid) continue
                val piid = cfg.invokeInt("getPlayerInterfaceId") ?: continue
                push(cfg, (levels[piid] ?: 1f) * (if (on) DUCK else 1f))
            }
            // Players started while ducked are ducked too.
            if (duckedUids.isNotEmpty() && watching == null) {
                val cb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        synchronized(lock) {
                            for (c in configs.orEmpty()) {
                                val u = c.invokeInt("getClientUid") ?: continue
                                if (u !in duckedUids) continue
                                val piid = c.invokeInt("getPlayerInterfaceId") ?: continue
                                push(c, (levels[piid] ?: 1f) * DUCK)
                            }
                        }
                    }
                }
                try {
                    am.registerAudioPlaybackCallback(cb, android.os.Handler(callbacks.looper))
                    watching = cb
                } catch (_: Throwable) {
                }
            } else if (duckedUids.isEmpty()) {
                watching?.let { runCatching { am.unregisterAudioPlaybackCallback(it) } }
                watching = null
            }
        }
    }

    private fun push(cfg: AudioPlaybackConfiguration, volume: Float): Boolean = try {
        val proxy = cfg.javaClass.getMethod("getPlayerProxy").invoke(cfg)
        if (proxy == null) false else {
            proxy.javaClass.getMethod("setVolume", Float::class.javaPrimitiveType).invoke(proxy, volume.coerceIn(0f, 1f))
            true
        }
    } catch (_: Throwable) {
        false
    }

    override fun players(): List<String> {
        val ctx = context ?: return emptyList()
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.activePlaybackConfigurations.mapNotNull { cfg ->
                // Only players actually running, and only sound you listen to (media, games,
                // voice calls, the assistant): not notifications, ringtones or alarms.
                val state = cfg.invokeInt("getPlayerState")
                if (state != null && state != PLAYER_STATE_STARTED) return@mapNotNull null
                val usage = try {
                    cfg.audioAttributes.usage
                } catch (_: Throwable) {
                    null
                }
                if (usage != null && usage !in AUDIBLE_USAGES) return@mapNotNull null
                val piid = cfg.invokeInt("getPlayerInterfaceId") ?: return@mapNotNull null
                val uid = cfg.invokeInt("getClientUid") ?: return@mapNotNull null
                val pkg = ctx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return@mapNotNull null
                "$piid|$uid|$pkg"
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun setPlayerVolume(piid: Int, volume: Float): Boolean {
        val ctx = context ?: return false
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val cfg = am.activePlaybackConfigurations.firstOrNull { it.invokeInt("getPlayerInterfaceId") == piid } ?: return false
            synchronized(lock) {
                val v = volume.coerceIn(0f, 1f)
                levels[piid] = v
                if (levels.size > 256) levels.keys.retainAll(am.activePlaybackConfigurations.mapNotNull { it.invokeInt("getPlayerInterfaceId") }.toSet() + piid)
                // Under a navigation prompt, the new level is ducked like the rest.
                val ducked = cfg.invokeInt("getClientUid") in duckedUids
                push(cfg, if (ducked) v * DUCK else v)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Whether a process of [packageName] is alive, from /proc (which the shell can read). When
     * nothing can be read at all, says yes, so a live app's volume is never forgotten by mistake.
     */
    override fun isPackageRunning(packageName: String): Boolean {
        return try {
            val dirs = File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null } ?: return true
            var readAny = false
            for (dir in dirs) {
                val cmdline = runCatching { File(dir, "cmdline").readText() }.getOrNull()
                if (cmdline.isNullOrEmpty()) continue
                readAny = true
                if (cmdline.takeWhile { it.code != 0 }.substringBefore(':') == packageName) return true
            }
            !readAny
        } catch (_: Throwable) {
            true
        }
    }

    override fun setRingerMode(mode: String): String = run("cmd", "audio", "set-ringer-mode", mode)

    override fun setPlaysAlongside(packageName: String, ignore: Boolean): String {
        if (!PACKAGE.matches(packageName)) return "ERROR: not a package name"
        return run("appops", "set", packageName, "TAKE_AUDIO_FOCUS", if (ignore) "ignore" else "allow")
    }

    override fun playsAlongside(): List<String> =
        run("appops", "query-op", "TAKE_AUDIO_FOCUS", "ignore")
            .lineSequence()
            .map { it.trim() }
            .filter { PACKAGE.matches(it) }
            .toList()

    override fun setLoudnessGain(millibels: Int): String {
        val gain = millibels.coerceIn(0, MAX_GAIN_MB)
        if (gain == 0) {
            releaseEnhancer()
            return ""
        }
        return try {
            val fx = enhancer ?: createEnhancer() ?: return "ERROR: no loudness effect here"
            enhancer = fx
            fx.setTargetGain(gain)
            if (!fx.enabled) fx.enabled = true
            ""
        } catch (e: Throwable) {
            releaseEnhancer()
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** The whole output first; where that's refused, the session of something playing now. */
    private fun createEnhancer(): LoudnessEnhancer? =
        runCatching { LoudnessEnhancer(0).also { it.enabled = true } }.getOrNull()
            ?: runCatching {
                val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
                val session = am.activePlaybackConfigurations.firstNotNullOfOrNull { cfg ->
                    cfg.invokeInt("getSessionId")?.takeIf { it != 0 }
                } ?: return null
                LoudnessEnhancer(session).also { it.enabled = true }
            }.getOrNull()

    private fun releaseEnhancer() {
        runCatching { enhancer?.release() }
        enhancer = null
    }

    private fun Any.invokeInt(method: String): Int? = try {
        javaClass.getMethod(method).invoke(this) as? Int
    } catch (_: Throwable) {
        null
    }

    private fun run(vararg cmd: String): String = try {
        val process = Runtime.getRuntime().exec(cmd)
        val out = process.inputStream.bufferedReader().readText() + process.errorStream.bufferedReader().readText()
        process.waitFor()
        out.trim()
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }

    private companion object {
        val PACKAGE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        /** AudioPlaybackConfiguration.PLAYER_STATE_STARTED. */
        const val PLAYER_STATE_STARTED = 2

        /** Unknown, media, voice communication, game, assistant. */
        val AUDIBLE_USAGES = setOf(0, 1, 2, 14, 16)

        /** +15 dB, the most the boost ever asks for. */
        const val MAX_GAIN_MB = 1500

        /** How far a duck lowers a player: Android's own figure. */
        const val DUCK = 0.2f
    }
}
