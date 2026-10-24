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

/**
 * Hosts the island. An accessibility service is the one ordinary app component that
 * can draw over the status bar and lock screen, which the island needs.
 * It reads nothing on screen.
 */
class IslandAccessibilityService : AccessibilityService() {

    private var host: IslandHost? = null
    private var scope: CoroutineScope? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        IslandSettings.init(this)
        val h = IslandHost(this)
        host = h
        IslandRepository.hostActive.value = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            IslandSettings.state.collect { prefs -> h.sync(prefs.enabled) }
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
        scope?.cancel()
        scope = null
        host?.detach()
        host = null
        IslandRepository.hostActive.value = false
    }
}
