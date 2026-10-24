package dev.continuum.fx.setup

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import rikka.shizuku.Shizuku

/** What the app has been granted, shown as the steps of the setup sheet. */
data class PrivilegeState(
    val recordAudio: Boolean = false,
    val notifications: Boolean = false,
    val dump: Boolean = false,
    val projectMedia: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
) {
    /** Everything required before processing can start. */
    val ready: Boolean get() = recordAudio && dump
}

enum class ShizukuState { NOT_RUNNING, NEEDS_PERMISSION, READY }

object Privileges {

    private const val DUMP = "android.permission.DUMP"
    private const val OP_PROJECT_MEDIA = "android:project_media"

    fun read(context: Context): PrivilegeState = PrivilegeState(
        recordAudio = granted(context, Manifest.permission.RECORD_AUDIO),
        notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
        dump = granted(context, DUMP),
        projectMedia = projectMediaAllowed(context),
        shizuku = shizukuState(),
    )

    fun adbCommands(packageName: String): String =
        "adb shell pm grant $packageName $DUMP\n" +
            "adb shell appops set $packageName PROJECT_MEDIA allow"

    fun shizukuState(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NEEDS_PERMISSION
        }
    } catch (_: Throwable) {
        ShizukuState.NOT_RUNNING
    }

    /**
     * Runs the same two commands as the ADB instructions, as the shell user through
     * Shizuku. Blocks, so call it off the main thread.
     */
    fun grantWithShizuku(packageName: String): Boolean {
        val grant = shizukuExec("pm grant $packageName $DUMP")
        // Optional: lets capture start without the system confirmation dialog.
        shizukuExec("appops set $packageName PROJECT_MEDIA allow")
        return grant
    }

    private fun shizukuExec(command: String): Boolean = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process
        process.waitFor() == 0
    } catch (_: Throwable) {
        false
    }

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun projectMediaAllowed(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(OP_PROJECT_MEDIA, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) {
        false
    }
}
