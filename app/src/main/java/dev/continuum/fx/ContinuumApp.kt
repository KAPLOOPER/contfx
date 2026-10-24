package dev.continuum.fx

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.continuum.fx.data.ProfileStore

class ContinuumApp : Application() {

    lateinit var store: ProfileStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        val channel = NotificationChannel(
            CHANNEL_PROCESSING,
            "Audio processing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while Continuum FX is processing other apps' audio"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_PROCESSING = "processing"
    }
}
