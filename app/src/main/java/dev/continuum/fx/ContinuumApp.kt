package dev.continuum.fx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.continuum.fx.audio.EngineSettings
import dev.continuum.fx.audio.EngineStatus
import dev.continuum.fx.audio.FxEnvironment
import dev.continuum.fx.audio.FxStore
import dev.continuum.fx.audio.SpatialStore
import dev.continuum.fx.audio.HeardApps
import dev.continuum.fx.audio.OutputWatcher
import dev.continuum.fx.island.IslandRepository
import dev.continuum.fx.tiles.ContinuumTile
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import dev.continuum.fx.data.ProfileStore
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.volume.VolumeModel
import dev.continuum.fx.volume.VolumeSettings
import dev.continuum.fx.volume.VolumeStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class ContinuumApp : Application() {

    lateinit var store: ProfileStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        IslandSettings.init(this)
        EngineSettings.init(this)
        FxStore.init(this)
        SpatialStore.init(this)
        HeardApps.init(this)
        VolumeSettings.init(this)
        VolumeModel.start(this)
        followOutputs()
        FxEnvironment.start(this)
        keepTilesCurrent()
        val channel = NotificationChannel(
            CHANNEL_PROCESSING,
            "Audio processing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Continuum FX is processing other apps' audio"
            setShowBadge(false)
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            "Updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Says when Continuum FX has updated itself"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, updates))
    }

    /** Switches to the EQ linked to whatever the sound is now playing through. */
    private fun followOutputs() {
        OutputWatcher.start(this)
        MainScope().launch {
            OutputWatcher.current.filterNotNull().distinctUntilChangedBy { it.key }.collect { device ->
                val lib = store.library
                val linked = lib.profileFor(device.key) ?: return@collect
                if (linked.id != lib.activeId) {
                    store.select(linked.id)
                    OutputWatcher.switched.tryEmit("${linked.name} for ${device.name}")
                }
            }
        }
    }

    /** Quick Settings tiles redraw whenever what they show changes. */
    private fun keepTilesCurrent() {
        MainScope().launch {
            combine(
                EngineStatus.running,
                store.state.map { it.active.name to it.eqEnabled },
                IslandSettings.state.map { it.enabled },
                IslandRepository.hostActive,
                combine(VolumeStatus.serviceOn, VolumeSettings.state.map { it.enabled }) { a, b -> a to b },
            ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
                .distinctUntilChanged()
                .collect { ContinuumTile.refresh(this@ContinuumApp) }
        }
    }

    companion object {
        const val CHANNEL_PROCESSING = "processing"
        const val CHANNEL_UPDATES = "updates"
    }
}
