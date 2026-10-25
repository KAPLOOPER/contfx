package dev.continuum.fx.volume

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.continuum.fx.BuildConfig
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** A player making sound now, as the helper sees it. */
data class Player(val piid: Int, val uid: Int, val pkg: String)

/**
 * The app's end of the Shizuku helper ([VolumeHelperService]). Binds whenever Shizuku is
 * running and has said yes, and stays bound while the app is alive; the helper process goes
 * away with it. Every call is optional: without Shizuku, the panel still moves the system's
 * streams, and only per-app volume, silent-without-Do-Not-Disturb, playing alongside and the
 * boost (without the EQ on) wait for it.
 *
 * Blocking calls are for background threads; the rest run on the helper's own thread.
 */
object VolumeHelper {
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    @Volatile
    private var service: IVolumeHelper? = null
    private var appContext: Context? = null
    private var binding = false
    private val main = Handler(Looper.getMainLooper())

    /** One thread for every call, so they reach the helper in the order they were made. */
    private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "ContinuumVolumeHelper") }

    private val received = Shizuku.OnBinderReceivedListener { main.post { connect() } }
    private val dead = Shizuku.OnBinderDeadListener { main.post { lost() } }

    private val args: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(ComponentName(appContext!!.packageName, VolumeHelperService::class.java.name))
            .daemon(false)
            .processNameSuffix("volume")
            .debuggable(BuildConfig.DEBUG)
            // A new build always gets a fresh helper rather than one left running from the last.
            .version(BuildConfig.BUILD_NUMBER)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                service = IVolumeHelper.Stub.asInterface(binder)
                _ready.value = true
            } else {
                lost()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = lost()
    }

    fun init(context: Context) {
        if (appContext != null) {
            connect()
            return
        }
        appContext = context.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky(received)
            Shizuku.addBinderDeadListener(dead)
        } catch (_: Throwable) {
        }
        connect()
    }

    /** Binds when Shizuku is up and allowed; quietly does nothing otherwise. */
    fun connect() {
        if (appContext == null || service != null || binding) return
        if (Privileges.shizukuState() != ShizukuState.READY) return
        binding = true
        try {
            Shizuku.bindUserService(args, connection)
        } catch (_: Throwable) {
            binding = false
            return
        }
        // Shizuku answers a bind it can't do with silence.
        main.postDelayed({ if (binding && service == null) binding = false }, BIND_TIMEOUT_MS)
    }

    private fun lost() {
        binding = false
        service = null
        _ready.value = false
    }

    private inline fun <T> call(fallback: T, block: (IVolumeHelper) -> T): T {
        val s = service ?: return fallback
        return try {
            block(s)
        } catch (_: Exception) {
            main.post { lost() }
            fallback
        }
    }

    // ---- Blocking: call from a background thread. ----

    fun players(): List<Player> = call(emptyList()) { s ->
        s.players().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            Player(parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1].toIntOrNull() ?: 0, parts[2])
        }
    }

    fun setPlayerVolume(piid: Int, volume: Float): Boolean = call(false) { it.setPlayerVolume(piid, volume) }

    fun isPackageRunning(pkg: String): Boolean = call(true) { it.isPackageRunning(pkg) }

    fun playsAlongside(): Set<String> = call(emptySet()) { it.playsAlongside().toSet() }

    // ---- On the helper's thread. ----

    /** Ringer through Android's internal path; [onDone] says whether it took. */
    fun setRingerMode(mode: Int, onDone: (Boolean) -> Unit) {
        val name = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        }
        calls.execute {
            val out = call<String?>(null) { it.setRingerMode(name) }
            val ok = out != null && !FAILURE.containsMatchIn(out)
            onDone(ok)
        }
    }

    /**
     * Sends the apps that play alongside; [onDone] gets null when it's done, otherwise why not
     * (no helper, or a phone that won't let it look after audio focus).
     */
    fun setAlongside(packages: Set<String>, onDone: (String?) -> Unit) {
        calls.execute {
            val out = call<String?>("Shizuku isn't connected.") { it.setAlongside(packages.toList()) }
            onDone(
                when {
                    out == null -> "Shizuku isn't connected."
                    out.isEmpty() -> null
                    else -> "This phone won't let the helper look after audio focus ($out)."
                },
            )
        }
    }

    /** Undoes the audio-focus app-op earlier builds used (most players won't play with it). */
    fun setPlaysAlongside(pkg: String, on: Boolean, onDone: (Boolean) -> Unit) {
        calls.execute {
            val out = call<String?>(null) { it.setPlaysAlongside(pkg, on) }
            onDone(out != null && !FAILURE.containsMatchIn(out))
        }
    }

    @Volatile
    private var pendingGain: Int? = null

    /** Only the newest gain matters: earlier ones still waiting are skipped. */
    fun setLoudnessGain(millibels: Int) {
        if (service == null && millibels != 0) return
        pendingGain = millibels
        calls.execute {
            val gain = pendingGain ?: return@execute
            pendingGain = null
            call(Unit) { it.setLoudnessGain(gain) }
        }
    }

    private val FAILURE = Regex("error|exception|unknown|usage|not found", RegexOption.IGNORE_CASE)
    private const val BIND_TIMEOUT_MS = 15_000L
}
