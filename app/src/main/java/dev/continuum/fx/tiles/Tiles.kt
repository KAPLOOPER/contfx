package dev.continuum.fx.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.continuum.fx.ContinuumApp
import dev.continuum.fx.MainActivity
import dev.continuum.fx.audio.Processing
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.setup.Privileges
import dev.continuum.fx.volume.VolumeService
import dev.continuum.fx.volume.VolumeSettings
import dev.continuum.fx.volume.VolumeStatus
import dev.continuum.fx.volume.VolumeTrampoline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Shared bits for Continuum's Quick Settings tiles. */
abstract class ContinuumTile : TileService() {
    private var scope: CoroutineScope? = null

    /** Starts following whatever the tile shows, for as long as the shade is open. */
    abstract fun CoroutineScope.follow()

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.follow() }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    protected fun show(active: Boolean, subtitle: String?) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        tile.updateTile()
    }

    /** Opens Continuum FX (at [action]) and folds the shade away. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun openApp(action: String? = null) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (action != null) intent.action = action
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the shade to redraw both tiles. */
        fun refresh(context: Context) {
            for (cls in listOf(EqTile::class.java, IslandTile::class.java, VolumeTile::class.java)) {
                try {
                    requestListeningState(context, ComponentName(context, cls))
                } catch (_: Exception) {
                }
            }
        }
    }
}

/**
 * Continuum EQ: shows whether processing is on and which EQ is playing. Tap to stop, or
 * to start (which opens the app for Android's capture prompt).
 */
class EqTile : ContinuumTile() {
    override fun CoroutineScope.follow() {
        val store = (application as ContinuumApp).store
        launch {
            combine(EngineStatus.running, store.state) { running, lib -> running to lib }.collect { (running, lib) ->
                show(running, if (!running) "Off" else if (lib.eqEnabled) lib.active.name else "EQ off")
            }
        }
    }

    override fun onClick() {
        super.onClick()
        if (EngineStatus.running.value) {
            Processing.stop(this)
        } else {
            openApp(MainActivity.ACTION_START_PROCESSING)
        }
    }
}

/** The island: on or off from the shade. */
class IslandTile : ContinuumTile() {
    override fun CoroutineScope.follow() {
        launch {
            combine(IslandSettings.state, IslandRepository.hostActive) { prefs, live -> prefs.enabled to live }.collect { (enabled, live) ->
                show(enabled && live, if (!live) "Set up" else if (enabled) "On" else "Off")
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val ready = Privileges.read(this).islandReady
        if (!ready) {
            openApp(MainActivity.ACTION_OPEN_ISLAND)
            return
        }
        IslandSettings.update { it.copy(enabled = !it.enabled) }
    }
}

/** The volume panel from the shade, for when the volume buttons aren't to hand (or have worn out). */
class VolumeTile : ContinuumTile() {
    override fun CoroutineScope.follow() {
        launch {
            combine(VolumeStatus.serviceOn, VolumeSettings.state) { on, prefs -> on && prefs.enabled }.collect { on ->
                show(on, if (on) "Open" else "Set up")
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (VolumeService.instance == null) {
            openApp(MainActivity.ACTION_OPEN_VOLUME)
            return
        }
        val intent = Intent(this, VolumeTrampoline::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
