package dev.continuum.fx

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.continuum.fx.audio.CaptureService
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.data.ProfileStore
import dev.continuum.fx.dsp.EqImport
import dev.continuum.fx.island.IslandNotificationListener
import dev.continuum.fx.island.IslandPrefs
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.setup.PrivilegeState
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.setup.ShizukuState
import dev.continuum.fx.ui.AppActions
import dev.continuum.fx.ui.AppScreen
import dev.continuum.fx.ui.Section
import dev.continuum.fx.ui.UiState
import dev.continuum.fx.ui.theme.ContinuumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity(), AppActions {

    private val store: ProfileStore get() = (application as ContinuumApp).store
    private val privileges = MutableStateFlow(PrivilegeState())
    private val message = MutableStateFlow<String?>(null)
    private val setupOpen = MutableStateFlow(false)
    private val section = MutableStateFlow(Section.Sound)
    private var afterShizukuPermission: (() -> Unit)? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.start(this, result.resultCode, data)
        } else {
            message.value = "Capture wasn't allowed, so processing didn't start."
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh()
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }
    private val shizukuPermission = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                afterShizukuPermission?.invoke()
            } else {
                message.value = "Shizuku permission was declined."
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(shizukuPermission)
        refresh()
        section.value = try {
            Section.valueOf(getSharedPreferences("ui", MODE_PRIVATE).getString("section", null) ?: Section.Sound.name)
        } catch (_: Exception) {
            Section.Sound
        }
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val library by store.state.collectAsState()
            val running by EngineStatus.running.collectAsState()
            val error by EngineStatus.error.collectAsState()
            val processing by EngineStatus.processing.collectAsState()
            val blocked by EngineStatus.blocked.collectAsState()
            val priv by privileges.collectAsState()
            val open by setupOpen.collectAsState()
            val msg by message.collectAsState()
            val sec by section.collectAsState()
            val island by IslandSettings.state.collectAsState()
            val islandLive by IslandRepository.hostActive.collectAsState()
            val islandFed by IslandRepository.listenerActive.collectAsState()
            ContinuumTheme {
                AppScreen(
                    state = UiState(
                        library, running, error, processing, blocked, priv, open, msg,
                        section = sec,
                        island = island,
                        islandLive = islandLive && island.enabled,
                        islandFed = islandFed,
                    ),
                    store = store,
                    actions = this,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(shizukuPermission)
        super.onDestroy()
    }

    private fun refresh() {
        privileges.value = Privileges.read(this)
    }

    // ---- AppActions ----

    override fun togglePower() {
        if (EngineStatus.running.value) {
            CaptureService.stop(this)
            return
        }
        refresh()
        if (!privileges.value.ready) {
            setupOpen.value = true
            return
        }
        EngineStatus.error.value = null
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    override fun requestRecord() {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun grantWithShizuku() {
        when (Privileges.shizukuState()) {
            ShizukuState.NOT_RUNNING ->
                message.value = "Shizuku isn't running. Start it in the Shizuku app, or use the ADB commands."
            ShizukuState.NEEDS_PERMISSION -> try {
                afterShizukuPermission = ::runShizukuGrant
                Shizuku.requestPermission(REQUEST_SHIZUKU)
            } catch (_: Throwable) {
                message.value = "Couldn't reach Shizuku. Try the ADB commands instead."
            }
            ShizukuState.READY -> runShizukuGrant()
        }
    }

    override fun grantIslandWithShizuku() {
        when (Privileges.shizukuState()) {
            ShizukuState.NOT_RUNNING ->
                message.value = "Shizuku isn't running. Start it in the Shizuku app, or use the settings buttons."
            ShizukuState.NEEDS_PERMISSION -> try {
                afterShizukuPermission = ::runIslandShizukuGrant
                Shizuku.requestPermission(REQUEST_SHIZUKU)
            } catch (_: Throwable) {
                message.value = "Couldn't reach Shizuku. Use the settings buttons instead."
            }
            ShizukuState.READY -> runIslandShizukuGrant()
        }
    }

    private fun runIslandShizukuGrant() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Privileges.grantIslandWithShizuku(packageName) }
            delay(700)
            refresh()
            val ready = privileges.value.islandReady
            if (ready) IslandSettings.update { it.copy(enabled = true) }
            message.value = when {
                ready -> "The island is on."
                ok -> "Nearly there. Check the steps that are left."
                else -> "Shizuku couldn't switch it on. Use the settings buttons instead."
            }
        }
    }

    override fun setSection(section: Section) {
        this.section.value = section
        setupOpen.value = false
        getSharedPreferences("ui", MODE_PRIVATE).edit().putString("section", section.name).apply()
    }

    override fun toggleIsland() {
        refresh()
        if (!privileges.value.islandReady) {
            setupOpen.value = true
            return
        }
        IslandSettings.update { it.copy(enabled = !it.enabled) }
    }

    override fun updateIsland(transform: (IslandPrefs) -> IslandPrefs) {
        IslandSettings.update(transform)
    }

    override fun openAccessibilitySettings() {
        startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun openListenerSettings() {
        val component = ComponentName(this, IslandNotificationListener::class.java)
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            null
        }
        if (detail == null || !startSafely(detail, quiet = true)) startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun openAppInfo() {
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    override fun copyIslandCommands() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB commands", Privileges.islandAdbCommands(packageName)))
        message.value = "Commands copied."
    }

    private fun startSafely(intent: Intent, quiet: Boolean = false): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        if (!quiet) message.value = "Couldn't open that settings page."
        false
    }

    private fun runShizukuGrant() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { Privileges.grantWithShizuku(packageName) }
            refresh()
            message.value = if (ok && privileges.value.dump) {
                "System access granted."
            } else {
                "Shizuku couldn't grant access. Try the ADB commands instead."
            }
        }
    }

    override fun copyAdbCommands() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("ADB commands", Privileges.adbCommands(packageName)))
        message.value = "Commands copied."
    }

    override fun importFile() {
        openDocument.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
    }

    override fun pasteFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            message.value = "The clipboard is empty. Copy the EQ text from squig.link first."
            return
        }
        importText(text, "Pasted EQ")
    }

    override fun shareActive() {
        val profile = store.library.active
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, profile.name)
            .putExtra(Intent.EXTRA_TITLE, "${profile.name}.txt")
            .putExtra(Intent.EXTRA_TEXT, EqImport.export(profile))
        startActivity(Intent.createChooser(send, "Share ${profile.name}"))
    }

    override fun setSetupOpen(open: Boolean) {
        if (open) refresh()
        setupOpen.value = open
    }

    override fun dismissMessage() {
        message.value = null
    }

    // ---- Import ----

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { importUri(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                when {
                    stream != null -> importUri(stream)
                    !text.isNullOrBlank() -> importText(text, intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared EQ")
                }
            }
        }
    }

    private fun importUri(uri: Uri) {
        try {
            val name = displayName(uri) ?: "Imported EQ"
            val text = contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes, Charsets.UTF_8)
            }
            if (text == null) {
                message.value = "That file is too large to be an EQ preset."
                return
            }
            importText(text, name)
        } catch (_: Exception) {
            message.value = "Couldn't open that file."
        }
    }

    private fun importText(text: String, name: String) {
        val result = EqImport.parse(text, name)
        val profile = result.profile
        if (profile == null) {
            message.value = result.error
            return
        }
        store.add(profile)
        val skipped = if (result.warnings.isEmpty()) "" else " · ${result.warnings.size} skipped"
        message.value = "Imported ${profile.name}: ${profile.bands.size} filters$skipped"
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val REQUEST_SHIZUKU = 42
        const val MAX_IMPORT_BYTES = 256 * 1024
    }
}
