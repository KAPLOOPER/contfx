package dev.continuum.fx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.continuum.fx.data.ProfileStore
import dev.continuum.fx.island.IslandSettings
import dev.continuum.fx.keyboard.KeyboardSettings

class ContinuumApp : Application() {

    lateinit var store: ProfileStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        IslandSettings.init(this)
        KeyboardSettings.init(this)
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

    companion object {
        const val CHANNEL_PROCESSING = "processing"
        const val CHANNEL_UPDATES = "updates"
    }
}
