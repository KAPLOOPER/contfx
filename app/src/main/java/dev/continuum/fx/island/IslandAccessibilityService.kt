package dev.continuum.fx.island

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the island. An accessibility service is the one ordinary app component that
 * can draw over the status bar and lock screen, which the island needs.
 * It reads nothing on screen.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var host: IslandHost? = null
    private var scope: CoroutineScope? = null
    private var battery: BatteryWatcher? = null
    private var clock: ClockWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandSettings.init(this)
        val h = IslandHost(this)
        host = h
        IslandRepository.hostActive.value = true
        battery = BatteryWatcher(this).also { it.start() }
        clock = ClockWatcher(this).also { it.start() }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            IslandSettings.state.collect { prefs ->
                h.sync(prefs.enabled)
                // Android's own pop-ups step aside while the island shows notifications.
                val hide = prefs.wantsPopupsHidden
                withContext(Dispatchers.IO) { PopupGuard.sync(this@IslandAccessibilityService, hide) }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.sync(IslandSettings.state.value.enabled)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        // Without the island, Android's pop-ups come back.
        if (scope != null) Thread { PopupGuard.sync(applicationContext, false) }.start()
        scope?.cancel()
        scope = null
        battery?.stop()
        battery = null
        clock?.stop()
        clock = null
        host?.detach()
        host = null
        IslandRepository.hostActive.value = false
    }
}
