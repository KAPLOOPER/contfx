package dev.continuum.fx.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.continuum.fx.BuildConfig
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A build published on the repository's Releases page. */
data class Release(
    val build: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    /** Android needs "Install unknown apps" switched on for Continuum FX first. */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Failed(val message: String, val release: Release?) : UpdateState
}

/**
 * Updates from inside the app. Checks the GitHub releases this app is built to, downloads
 * the newer APK and installs it over the top: silently through Shizuku when it's running,
 * otherwise through Android's installer, which asks once per update. Android only accepts
 * an update signed with the same key, so a tampered APK can't install over this one.
 */
object Updater {

    private const val PREFS = "updates"
    private const val AUTO_CHECK_EVERY = 6 * 60 * 60 * 1000L

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** The installer's confirmation screen, for the activity to show while it's in front. */
    val confirm = MutableStateFlow<Intent?>(null)

    @Volatile
    var installing: Release? = null
        private set

    /** True while the app's screen is in front, so it can show the installer's confirmation. */
    @Volatile
    var screenVisible = false

    val current: Int get() = BuildConfig.BUILD_NUMBER

    /** Downloads outlive the screen, so they run here rather than in the activity. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(context: Context, release: Release) {
        val app = context.applicationContext
        scope.launch { update(app, release) }
    }

    fun shouldAutoCheck(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastCheck", 0L)
        return System.currentTimeMillis() - last > AUTO_CHECK_EVERY
    }

    private fun busy() = state.value.let { it is UpdateState.Downloading || it is UpdateState.Installing || it is UpdateState.Checking }

    /** Looks for a newer build. A quiet check only speaks up if there is one. */
    suspend fun check(context: Context, quiet: Boolean) {
        if (busy()) return
        val before = state.value
        if (!quiet) state.value = UpdateState.Checking
        try {
            val latest = withContext(Dispatchers.IO) { latestRelease() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("lastCheck", System.currentTimeMillis()).apply()
            state.value = when {
                latest != null && latest.build > current -> UpdateState.Available(latest)
                quiet -> if (before is UpdateState.Available) UpdateState.Idle else before
                else -> UpdateState.UpToDate
            }
        } catch (_: Exception) {
            state.value = if (quiet) before else UpdateState.Failed("Couldn't reach GitHub. Check your connection and try again.", null)
        }
    }

    fun dismiss() {
        if (!busy()) state.value = UpdateState.Idle
    }

    /** Downloads and installs [release]. */
    suspend fun update(context: Context, release: Release) {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Installing) return
        try {
            val apk = download(context, release)
            install(context, apk, release)
        } catch (_: Exception) {
            state.value = UpdateState.Failed("The download didn't finish. Try again.", release)
        }
    }

    private suspend fun install(context: Context, apk: File, release: Release) {
        installing = release
        state.value = UpdateState.Installing(release)
        if (Privileges.shizukuState() == ShizukuState.READY) {
            // Through Shizuku the update installs without a prompt, and marks this app as its
            // own installer, so later updates can go through quietly on Android 12+ too.
            val ok = withContext(Dispatchers.IO) { installWithShizuku(context, apk) }
            if (ok) return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            state.value = UpdateState.NeedsPermission(release)
            return
        }
        try {
            withContext(Dispatchers.IO) { installWithSession(context, apk) }
        } catch (_: Exception) {
            state.value = UpdateState.Failed("Android couldn't start the install. Try again.", release)
        }
    }

    /** Called when Android reports back on an install. */
    fun onInstallResult(status: Int, message: String?) {
        val release = installing
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> state.value = UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> state.value = release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                state.value = UpdateState.Failed(
                    "This build is signed differently from the one on your phone. Uninstall once and " +
                        "install it from GitHub; after that, updates install in place.",
                    release,
                )
            PackageInstaller.STATUS_FAILURE_STORAGE -> state.value = UpdateState.Failed("There isn't enough space to install the update.", release)
            else -> state.value = UpdateState.Failed(message?.takeIf { it.isNotBlank() } ?: "The update didn't install.", release)
        }
    }

    // ---- GitHub ----

    private fun latestRelease(): Release? {
        val text = get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15")
        val list = JSONArray(text)
        var best: Release? = null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (o.optBoolean("draft")) continue
            val build = BUILD_TAG.find(o.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val assets = o.optJSONArray("assets") ?: continue
            var url: String? = null
            var size = 0L
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
            if (url.isNullOrBlank()) continue
            if (best == null || build > best.build) {
                best = Release(build, o.optString("name"), o.optString("body"), url, size)
            }
        }
        return best
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ContinuumFX/${BuildConfig.BUILD_NUMBER}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private fun get(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val name = "ContinuumFX-build${release.build}.apk"
        dir.listFiles()?.forEach { if (it.name != name) it.delete() }
        val file = File(dir, name)
        if (release.size > 0 && file.length() == release.size) return@withContext file

        state.value = UpdateState.Downloading(release, 0f)
        val part = File(dir, "$name.part")
        val conn = open(release.apkUrl)
        conn.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var read = 0L
            var shown = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                state.value = UpdateState.Downloading(release, read.toFloat() / total)
                            }
                        }
                    }
                }
            }
            if (release.size > 0 && part.length() != release.size) throw IOException("Incomplete download")
        } finally {
            conn.disconnect()
        }
        file.delete()
        if (!part.renameTo(file)) throw IOException("Couldn't save the update")
        file
    }

    // ---- Installing ----

    private fun installWithShizuku(context: Context, apk: File): Boolean = try {
        val process = Privileges.shizukuProcess("pm install -r -i ${context.packageName} -S ${apk.length()}")
        if (process == null) {
            false
        } else {
            process.outputStream.use { out -> apk.inputStream().use { it.copyTo(out) } }
            // On success Android replaces this app, ending this process before it returns.
            process.waitFor() == 0
        }
    } catch (_: Throwable) {
        false
    }

    private fun installWithSession(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private val BUILD_TAG = Regex("""build-(\d+)""")
}
