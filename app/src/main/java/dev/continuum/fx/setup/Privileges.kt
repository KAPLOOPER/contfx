package dev.continuum.fx.setup

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import dev.continuum.fx.island.IslandAccessibilityService
import dev.continuum.fx.island.IslandNotificationListener
import rikka.shizuku.Shizuku

/** What the app has been granted, shown as the steps of the setup sheet. */
data class PrivilegeState(
    val recordAudio: Boolean = false,
    val notifications: Boolean = false,
    val dump: Boolean = false,
    val projectMedia: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
    /** The island's accessibility service is switched on. */
    val islandOverlay: Boolean = false,
    /** The island's notification listener has access. */
    val islandListener: Boolean = false,
    /** Allowed to switch Android's notification pop-ups off while the island runs. */
    val popups: Boolean = false,
) {
    /** Everything required before processing can start. */
    val ready: Boolean get() = recordAudio && dump

    /** Everything the island needs. */
    val islandReady: Boolean get() = islandOverlay && islandListener
}

enum class ShizukuState { NOT_RUNNING, NEEDS_PERMISSION, READY }

object Privileges {

    private const val DUMP = "android.permission.DUMP"
    private const val OP_PROJECT_MEDIA = "android:project_media"
    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** For computers: let the island switch Android's pop-ups off. */
    fun popupsAdbCommand(packageName: String): String =
        "adb shell pm grant $packageName $WRITE_SECURE_SETTINGS"

    /** Lets the island switch Android's pop-ups off. Blocks; call it off the main thread. */
    fun grantPopupsWithShizuku(packageName: String): Boolean =
        shizukuExec("pm grant $packageName $WRITE_SECURE_SETTINGS")

    fun read(context: Context): PrivilegeState = PrivilegeState(
        recordAudio = granted(context, Manifest.permission.RECORD_AUDIO),
        notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS),
        dump = granted(context, DUMP),
        projectMedia = projectMediaAllowed(context),
        shizuku = shizukuState(),
        islandOverlay = accessibilityOn(context),
        islandListener = listenerOn(context),
        popups = granted(context, WRITE_SECURE_SETTINGS),
    )

    private fun accessibilityOn(context: Context): Boolean = try {
        val target = ComponentName(context, IslandAccessibilityService::class.java)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.any { ComponentName.unflattenFromString(it) == target } == true
    } catch (_: Throwable) {
        false
    }

    private fun listenerOn(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, IslandNotificationListener::class.java))
    } catch (_: Throwable) {
        false
    }

    private fun islandService(packageName: String) = "$packageName/$packageName.island.IslandAccessibilityService"
    private fun islandListener(packageName: String) = "$packageName/$packageName.island.IslandNotificationListener"

    /** For computers: unlock the restricted settings and give notification access. */
    fun islandAdbCommands(packageName: String): String =
        "adb shell appops set $packageName ACCESS_RESTRICTED_SETTINGS allow\n" +
            "adb shell cmd notification allow_listener ${islandListener(packageName)}"

    /**
     * Switches the island on through Shizuku: unlocks restricted settings, grants
     * notification access and adds the accessibility service to the enabled list,
     * keeping any services that are already on. Blocks; call it off the main thread.
     */
    fun grantIslandWithShizuku(packageName: String): Boolean {
        val service = islandService(packageName)
        shizukuExec("appops set $packageName ACCESS_RESTRICTED_SETTINGS allow")
        val listener = shizukuExec("cmd notification allow_listener ${islandListener(packageName)}")
        val script = "s=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$s\" in *$service*) ;; null|'') settings put secure enabled_accessibility_services $service ;; " +
            "*) settings put secure enabled_accessibility_services \"\$s:$service\" ;; esac; " +
            "settings put secure accessibility_enabled 1"
        val overlay = shizukuExec(script)
        return listener && overlay
    }

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
        shizukuProcess(command)?.waitFor() == 0
    } catch (_: Throwable) {
        false
    }

    /** Starts `sh -c command` as the shell user through Shizuku, or null if that isn't possible. */
    fun shizukuProcess(command: String): java.lang.Process? = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process
    } catch (_: Throwable) {
        null
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
